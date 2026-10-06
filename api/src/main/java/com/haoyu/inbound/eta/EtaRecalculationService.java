package com.haoyu.inbound.eta;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.eta.EtaPredictor.Prediction;
import com.haoyu.inbound.events.AvailabilityEvents;
import com.haoyu.inbound.events.EtaUpdatedEvent;
import com.haoyu.inbound.events.EventPublisher;
import com.haoyu.inbound.procurement.MilestoneType;
import com.haoyu.inbound.procurement.Shipment;
import com.haoyu.inbound.procurement.ShipmentMilestone;
import com.haoyu.inbound.procurement.ShipmentRepository;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EtaRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(EtaRecalculationService.class);

    /** Why a prediction was recomputed; kept in eta_prediction_log so accuracy can be scored per cause. */
    public enum Reason { MILESTONE, DAILY, STATS_REFRESH, SEED }

    public record RecalcSummary(int shipments, int changed, int failed) {}

    private final ShipmentRepository shipments;
    private final LaneStatsRepository laneStats;
    private final EventPublisher publisher;
    private final AvailabilityEvents availability;
    private final AppProperties props;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public EtaRecalculationService(ShipmentRepository shipments, LaneStatsRepository laneStats, EventPublisher publisher,
                                   AvailabilityEvents availability, AppProperties props, Clock clock, JdbcClient jdbc,
                                   TransactionTemplate tx) {
        this.shipments = shipments;
        this.laneStats = laneStats;
        this.publisher = publisher;
        this.availability = availability;
        this.props = props;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Re-scores every open shipment, one transaction each, so one bad row cannot stop the rest. */
    public RecalcSummary recalculateAllOpen(Reason reason) {
        List<Long> ids = jdbc.sql("""
                select s.id from shipment s join purchase_order po on po.id = s.po_id
                where po.status = 'OPEN' order by s.id
                """)
                .query(Long.class)
                .list();
        int changed = 0, failed = 0;
        for (long id : ids) {
            try {
                // calling recalculate() directly would bypass the @Transactional proxy; the outbox insert needs a transaction
                Boolean c = tx.execute(status -> recalculate(id, reason));
                if (Boolean.TRUE.equals(c)) changed++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("re-score of shipment {} failed: {}", id, e.toString());
            }
        }
        return new RecalcSummary(ids.size(), changed, failed);
    }

    /**
     * Recomputes the prediction from the database state, not from the triggering event, so replays and
     * out-of-order events converge on the same answer. Returns true when the prediction changed; then it
     * is logged, announced on shipment.eta-updated, and every SKU on the container gets an
     * availability.changed (the one trigger the storefront projection listens to).
     */
    @Transactional
    public boolean recalculate(long shipmentId, Reason reason) {
        Shipment shipment = shipments.require(shipmentId);
        ShipmentRepository.LaneOf lane = shipments.laneOf(shipmentId);
        Optional<ShipmentMilestone> latest = shipments.milestones(shipmentId).stream()
                .max(Comparator.comparing((ShipmentMilestone m) -> m.type().ordinal())
                        .thenComparing(ShipmentMilestone::occurredAt));
        Optional<LaneStats> stats = latest.flatMap(m ->
                laneStats.find(lane.originPort(), lane.destFcCode(), m.type(), MilestoneType.RECEIVED_FC));
        String laneLabel = lane.originPort() + "→" + lane.destFcCode();
        Prediction p = EtaPredictor.predict(shipment, latest, stats, laneLabel, LocalDate.now(clock), clock.getZone());

        boolean changed = !Objects.equals(p.arrival(), shipment.predictedArrival())
                || !Objects.equals(p.confidence().name(), shipment.predictedConfidence());
        if (!changed && Objects.equals(p.basis(), shipment.predictionBasis())) {
            return false;
        }
        if (!shipments.updatePrediction(shipmentId, shipment.version(), p.arrival(), p.confidence().name(), p.basis())) {
            // a concurrent milestone already advanced the row; its own recalculation will publish
            log.info("shipment {} changed concurrently, skipping this recalculation", shipmentId);
            return false;
        }
        if (!changed) {
            return false;   // same date and confidence, only the explanation was refreshed
        }
        jdbc.sql("""
                insert into eta_prediction_log (shipment_id, stage, reason, predicted_arrival, confidence, applied_days, sample_n)
                values (:id, :stage, :reason, :arrival, :confidence, :days, :n)
                """)
                .param("id", shipmentId)
                .param("stage", latest.map(m -> m.type().name()).orElse(shipment.currentStage().name()))
                .param("reason", reason.name())
                .param("arrival", p.arrival())
                .param("confidence", p.confidence().name())
                .param("days", p.appliedDays(), Types.INTEGER)
                .param("n", p.sampleN(), Types.INTEGER)
                .update();
        publisher.publish(props.topics().etaUpdated(), Long.toString(shipmentId),
                new EtaUpdatedEvent(shipmentId, shipment.poId(), shipment.predictedArrival(), p.arrival(),
                        p.confidence().name(), p.basis(), OffsetDateTime.now(clock)));
        shipments.skusOnShipment(shipmentId).forEach(sku -> availability.changed(sku, lane.destFcCode(), "eta changed"));
        return true;
    }
}
