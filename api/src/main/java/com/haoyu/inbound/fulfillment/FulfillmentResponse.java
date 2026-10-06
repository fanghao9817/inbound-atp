package com.haoyu.inbound.fulfillment;

import java.time.LocalDate;
import java.util.List;

/**
 * The naive answer (the original interview question): stock on hand first, then purchase orders in
 * arrival order. committedToOtherOrders reports what that rule ignores - units already promised to
 * scheduled and backordered orders - which is exactly what the ATP quote nets out.
 */
public record FulfillmentResponse(String sku, String fc, int requestedQuantity, int fulfilledQuantity, int remainingQuantity,
                                  int allocatedFromInventory, List<PurchaseOrderAllocation> purchaseOrderAllocations,
                                  boolean fullyFulfilled, LocalDate fulfilledBy, int committedToOtherOrders) {}
