package com.haoyu.inbound.procurement;

/**
 * What the warehouse counted for one SKU when a container was received. Omitted lines are received
 * in full; qtyDamaged units count against the PO but never become sellable stock.
 */
public record ReceivedLine(String sku, Integer qtyReceived, Integer qtyDamaged) {}
