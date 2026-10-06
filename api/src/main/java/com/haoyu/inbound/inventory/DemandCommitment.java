package com.haoyu.inbound.inventory;

import java.time.LocalDate;

/** A row of the demand_commitment view: a SCHEDULED or BACKORDERED order at its promised date. */
public record DemandCommitment(long id, long skuId, long fcId, int qty, LocalDate needBy, String reference, Long orderId) {}
