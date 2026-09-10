package com.haoyu.inbound.fulfillment;

import java.time.LocalDate;
import java.util.List;

public record FulfillmentResponse(String sku, String fc, int requestedQuantity, int fulfilledQuantity, int remainingQuantity,
                                  int allocatedFromInventory, List<PurchaseOrderAllocation> purchaseOrderAllocations,
                                  boolean fullyFulfilled, LocalDate fulfilledBy) {}
