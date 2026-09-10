package com.haoyu.inbound.catalog;

public record FulfillmentCenter(long id, String code, String name, String region, int receivingBufferDays) {}
