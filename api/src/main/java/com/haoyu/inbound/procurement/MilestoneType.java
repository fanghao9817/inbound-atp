package com.haoyu.inbound.procurement;

/** Ordered stages of an inbound container. Ordinal order is the business order. */
public enum MilestoneType {
    BOOKED,
    DEPARTED_ORIGIN,
    ARRIVED_DEST_PORT,
    CUSTOMS_CLEARED,
    RECEIVED_FC;

    public boolean isAfter(MilestoneType other) {
        return ordinal() > other.ordinal();
    }
}
