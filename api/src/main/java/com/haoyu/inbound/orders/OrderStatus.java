package com.haoyu.inbound.orders;

/**
 * RESERVED: stock on hand is set aside (inventory_position.reserved) and waits to be picked.
 * SCHEDULED: future-dated demand (B2B) that ATP covers by its date; nothing is locked until it is due.
 * BACKORDERED: promised against inbound stock because nothing is free today (or not by the requested date).
 * SHIPPED / CANCELLED: final. REJECTED: no date could be promised within the horizon (lost demand).
 * SCHEDULED and BACKORDERED orders are the demand_commitment view that ATP subtracts.
 */
public enum OrderStatus {
    RESERVED, SCHEDULED, BACKORDERED, SHIPPED, CANCELLED, REJECTED;

    public boolean isCommitment() {
        return this == SCHEDULED || this == BACKORDERED;
    }
}
