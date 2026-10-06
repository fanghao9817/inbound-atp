package com.haoyu.inbound.eta;

import com.haoyu.inbound.eta.EtaPredictor.Prediction;
import com.haoyu.inbound.procurement.MilestoneType;
import com.haoyu.inbound.procurement.Shipment;
import com.haoyu.inbound.procurement.ShipmentMilestone;
import com.haoyu.inbound.procurement.ShipmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the real predictor on a hypothetical milestone, so a visitor can see how a container reaching
 * port (say) would move its date and confidence - without writing a fake milestone into the system of
 * record that the carrier feed would then be unable to correct.
 */
@Service
public class EtaPreviewService {

    public record Preview(LocalDate currentArrival, String currentConfidence, LocalDate previewArrival, String previewConfidence,
                          String basis) {}

    private final ShipmentRepository shipments;
    private final LaneStatsRepository laneStats;
    private final Clock clock;

    public EtaPreviewService(ShipmentRepository shipments, LaneStatsRepository laneStats, Clock clock) {
        this.shipments = shipments;
        this.laneStats = laneStats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Preview preview(long shipmentId, MilestoneType type, OffsetDateTime occurredAt) {
        if (occurredAt.isAfter(OffsetDateTime.now(clock).plusMinutes(15))) {
            throw new IllegalArgumentException("a milestone cannot happen in the future");
        }
        Shipment shipment = shipments.require(shipmentId);
        var lane = shipments.laneOf(shipmentId);
        var hypothetical = new ShipmentMilestone(0, shipmentId, type, occurredAt, "MANUAL", UUID.randomUUID(), OffsetDateTime.now(clock));
        Prediction p = EtaPredictor.predict(shipment, Optional.of(hypothetical),
                laneStats.find(lane.originPort(), lane.destFcCode(), type, MilestoneType.RECEIVED_FC),
                lane.originPort() + "→" + lane.destFcCode(), LocalDate.now(clock), clock.getZone());
        return new Preview(shipment.predictedArrival(), shipment.predictedConfidence(), p.arrival(), p.confidence().name(), p.basis());
    }
}
