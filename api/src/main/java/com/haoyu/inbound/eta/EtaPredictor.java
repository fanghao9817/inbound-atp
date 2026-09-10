package com.haoyu.inbound.eta;

import com.haoyu.inbound.procurement.MilestoneType;
import com.haoyu.inbound.procurement.Shipment;
import com.haoyu.inbound.procurement.ShipmentMilestone;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Predicts when a container will be received at the fulfillment center.
 *
 * <p>Promise dates are built on the P80 of historical transit times for the lane (origin port →
 * FC) measured from the latest milestone, not on the mean: under-promising and over-delivering
 * keeps customer trust, and a P80 estimate is what the ATP layer needs to be right most of the
 * time. Confidence reflects both the sample size and how far along the container is.
 */
public final class EtaPredictor {

    public enum Confidence { LOW, MEDIUM, HIGH }

    public record Prediction(LocalDate arrival, Confidence confidence, String basis) {}

    private EtaPredictor() {}

    public static Prediction predict(Shipment shipment, Optional<ShipmentMilestone> latest,
                                     Optional<LaneStats> stats, String lane, LocalDate today) {
        if (latest.isEmpty()) {
            return fallback(shipment, today, "No milestone received yet");
        }
        ShipmentMilestone m = latest.get();
        LocalDate milestoneDate = m.occurredAt().toLocalDate();
        if (m.type() == MilestoneType.RECEIVED_FC) {
            return new Prediction(milestoneDate, Confidence.HIGH, "Received at fulfillment center");
        }
        if (stats.isEmpty()) {
            return fallback(shipment, today, "No lane history for " + lane + " from " + m.type());
        }
        LaneStats s = stats.get();
        long p80 = (long) Math.ceil(s.p80Days().doubleValue());
        LocalDate arrival = milestoneDate.plusDays(p80);
        if (arrival.isBefore(today)) {
            // history says it should already be here; it is late, promise no earlier than tomorrow
            arrival = today.plusDays(1);
        }
        Confidence confidence = confidence(s.sampleN(), m.type());
        String basis = String.format("P80 of %d shipments %s from %s: %.1f d (P50 %.1f d), milestone on %s",
                s.sampleN(), lane, m.type(), s.p80Days(), s.p50Days(), milestoneDate);
        return new Prediction(arrival, confidence, basis);
    }

    private static Prediction fallback(Shipment shipment, LocalDate today, String why) {
        LocalDate planned = shipment.plannedArrival();
        LocalDate arrival = planned.isBefore(today) ? today.plusDays(1) : planned;
        return new Prediction(arrival, Confidence.LOW, why + "; using carrier plan " + planned);
    }

    private static Confidence confidence(int sampleN, MilestoneType stage) {
        boolean lateStage = !MilestoneType.ARRIVED_DEST_PORT.isAfter(stage); // stage >= ARRIVED_DEST_PORT
        if (sampleN >= 30) return lateStage ? Confidence.HIGH : Confidence.MEDIUM;
        if (sampleN >= 10) return lateStage ? Confidence.MEDIUM : Confidence.LOW;
        return Confidence.LOW;
    }
}
