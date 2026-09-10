package com.haoyu.inbound.inventory;

import java.time.LocalDate;

public record DemandCommitment(long id, long skuId, long fcId, int qty, LocalDate needBy, String reference) {}
