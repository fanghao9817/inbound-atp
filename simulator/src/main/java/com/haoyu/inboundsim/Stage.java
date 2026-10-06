package com.haoyu.inboundsim;

/** Same vocabulary and order as the API's MilestoneType. */
public enum Stage {
    BOOKED, DEPARTED_ORIGIN, ARRIVED_DEST_PORT, CUSTOMS_CLEARED, RECEIVED_FC;

    public Stage next() {
        return this == RECEIVED_FC ? null : values()[ordinal() + 1];
    }

    /** Who reports reaching this stage. */
    public String source() {
        return switch (this) {
            case BOOKED -> "BUYER";
            case DEPARTED_ORIGIN, ARRIVED_DEST_PORT -> "CARRIER_EDI";
            case CUSTOMS_CLEARED -> "CUSTOMS_BROKER";
            case RECEIVED_FC -> "WMS";
        };
    }
}
