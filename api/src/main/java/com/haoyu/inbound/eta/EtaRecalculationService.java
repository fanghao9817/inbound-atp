package com.haoyu.inbound.eta;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.eta.EtaPredictor.Prediction;
import com.haoyu.inbound.events.EtaUpdatedEvent;
import com.haoyu.inbound.events.EventPublisher;
import com.haoyu.inbound.procurement.MilestoneType;
import com.haoyu.inbound.procurement.Shipment;
import com.haoyu.inbound.procurement.ShipmentMilestone;
import com.haoyu.inbound.procurement.ShipmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EtaRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(EtaRecalculationService.class);

    private final ShipmentRepository shipments;
    private final LaneStatsRepository laneStats;
    private final EventPublisher publisher;
    private final AppProperties props;
    private final Clock clock;

    public EtaRecalculationService(ShipmentRepository shipments, LaneStatsRepository laneStats,
                                   EventPublisher publisher, AppProperties props, Clock clock) {
        this.shipments = shipments;
        this.laneStats = laneStats;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Recomputes the prediction from the database state, not from the triggering event, so replays
     * and out-of-order events converge on the same answer (idempotent by construction).
     */
    @Transactional
    public Prediction recalculate(long shipmentId) {
        Shipment shipment = shipments.require(shipmentId);
        ShipmentRepository.LaneOf lane = shipments.laneOf(shipmentId);
        Optional<ShipmentMilestone> latest = shipments.milestones(shipmentId).stream()
                .max(Comparator.comparing((ShipmentMilestone m) -> m.type().ordinal())
                        .thenComparing(ShipmentMilestone::occurredAt));
        Optional<LaneStats> stats = latest.flatMap(m ->
                laneStats.find(lane.originPort(), lane.destFcCode(), m.type(), MilestoneType.RECEIVED_FC));
        String laneLabel = lane.originPort() + "→" + lane.destFcCode();
        Prediction p = EtaPredictor.predict(shipment, latest, stats, laneLabel, LocalDate.now(clock));

        boolean changed = !Objects.equals(p.arrival(), shipment.predictedArrival())
                || !Objects.equals(p.confidence().name(), shipment.predictedConfidence());
        if (!changed && Objects.equals(p.basis(), shipment.predictionBasis())) {
            return p;
        }
        boolean updated = shipments.updatePrediction(shipmentId, shipment.version(), p.arrival(), p.confidence().name(), p.basis());
        if (!updated) {
            // a concurrent milestone already advanced the row; its own recalculation will publish
            log.info("shipment {} changed concurrently, skipping this recalculation", shipmentId);
            return p;
        }
        if (changed) {
            publisher.publishAfterCommit(props.topics().etaUpdated(), Long.toString(shipmentId),
                    new EtaUpdatedEvent(shipmentId, shipment.poId(), shipment.predictedArrival(), p.arrival(),
                            p.confidence().name(), p.basis(), OffsetDateTime.now(clock)));
        }
        return p;
    }
}
