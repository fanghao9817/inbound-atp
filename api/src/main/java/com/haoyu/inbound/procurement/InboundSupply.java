package com.haoyu.inbound.procurement;

import java.time.LocalDate;

/**
 * One open purchase-order line for a SKU heading to a fulfillment center, joined with its shipment.
 * This is the read model the ATP and fulfillment calculations consume.
 */
public record InboundSupply(long poId, String poNumber, long poLineId, long skuId, long destFcId,
                            int qtyOrdered, int qtyReceived, LocalDate plannedArrival, LocalDate predictedArrival,
                            String predictedConfidence, MilestoneType currentStage, Long shipmentId) {

    public int qtyOutstanding() {
        return Math.max(qtyOrdered - qtyReceived, 0);
    }

    public LocalDate effectiveArrival() {
        return predictedArrival != null ? predictedArrival : plannedArrival;
    }
}
