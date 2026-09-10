package com.haoyu.inbound.inventory;

public record InventoryPosition(long skuId, long fcId, int onHand, int reserved) {

    /** Units physically present and not yet allocated to an order. */
    public int availableNow() {
        return Math.max(onHand - reserved, 0);
    }
}
