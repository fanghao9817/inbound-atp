package com.haoyu.inbound.orders;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CustomerOrder(long id, String orderRef, Channel channel, long skuId, long fcId, int qty, OrderStatus status,
                            LocalDate promiseDate, LocalDate needBy, OffsetDateTime createdAt, OffsetDateTime reservedAt,
                            OffsetDateTime shippedAt, OffsetDateTime cancelledAt) {}
