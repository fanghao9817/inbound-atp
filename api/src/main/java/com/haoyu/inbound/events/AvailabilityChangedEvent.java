package com.haoyu.inbound.events;

import java.time.OffsetDateTime;

/**
 * Published on availability.changed whenever an input to ATP changes for one SKU at one FC: stock
 * received, reserved, released or shipped, or a backorder promised against inbound stock.
 * Key = "SKU|FC", so all changes to one position stay in order on one partition.
 */
public record AvailabilityChangedEvent(String sku, String fc, String reason, OffsetDateTime at) {

    public static String key(String sku, String fc) {
        return sku + "|" + fc;
    }
}
