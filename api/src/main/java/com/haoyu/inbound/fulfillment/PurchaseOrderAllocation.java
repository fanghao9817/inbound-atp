package com.haoyu.inbound.fulfillment;

import java.time.LocalDate;

/** How much of the request one inbound purchase order covers, and when that stock is expected. */
public record PurchaseOrderAllocation(long purchaseOrderId, String poNumber, int allocatedQuantity, LocalDate expectedAt,
                                      String confidence) {}
