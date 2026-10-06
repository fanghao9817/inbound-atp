package com.haoyu.inbound.orders;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** An order as the API returns it: with codes instead of ids. */
public record OrderView(long id, String orderRef, String channel, String sku, String fc, int qty, String status,
                        LocalDate promiseDate, LocalDate needBy, OffsetDateTime createdAt, OffsetDateTime reservedAt,
                        OffsetDateTime shippedAt, OffsetDateTime cancelledAt) {}
