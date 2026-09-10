package com.haoyu.inbound.fulfillment;

import java.time.LocalDate;

/** Outstanding quantity on one purchase order, as read by {@link FulfillmentJdbcDao}. */
public record InboundLine(long purchaseOrderId, String poNumber, int qtyOutstanding, LocalDate expectedAt, String confidence) {}
