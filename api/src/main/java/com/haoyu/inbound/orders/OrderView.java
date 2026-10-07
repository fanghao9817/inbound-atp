package com.haoyu.inbound.orders;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An order as the API returns it: with codes instead of ids. {@code status} is where the order is now;
 * {@code placedStatus} is what was decided when it was placed (RESERVED, SCHEDULED, BACKORDERED, REJECTED).
 */
public record OrderView(long id, String orderRef, String channel, String origin, String sku, String fc, int qty, String status,
                        String placedStatus, LocalDate promiseDate, LocalDate firstPromiseDate, LocalDate needBy, OffsetDateTime createdAt,
                        OffsetDateTime reservedAt, OffsetDateTime shippedAt, OffsetDateTime cancelledAt) {}
