package com.haoyu.inbound.procurement;

import java.time.LocalDate;

public record Shipment(long id, long poId, String carrier, String containerNo, LocalDate plannedDeparture,
                       LocalDate plannedArrival, LocalDate predictedArrival, String predictedConfidence,
                       String predictionBasis, MilestoneType currentStage, int version) {

    /** The arrival date planning should use: our prediction when we have one, the carrier's plan otherwise. */
    public LocalDate effectiveArrival() {
        return predictedArrival != null ? predictedArrival : plannedArrival;
    }
}
