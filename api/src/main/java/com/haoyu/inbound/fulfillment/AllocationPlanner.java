package com.haoyu.inbound.fulfillment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure allocation rule: consume on-hand stock first, then cover the remaining gap from inbound
 * purchase orders in order of expected arrival, taking from each PO only what is still needed.
 * A PO that is not touched does not appear in the plan.
 */
public final class AllocationPlanner {

    private AllocationPlanner() {}

    public static FulfillmentResponse plan(String sku, String fc, int requested, int inventoryAvailable,
                                           List<InboundLine> inbound, LocalDate today) {
        int fromInventory = Math.min(Math.max(inventoryAvailable, 0), requested);
        int gap = requested - fromInventory;
        LocalDate fulfilledBy = today;

        List<PurchaseOrderAllocation> allocations = new ArrayList<>();
        for (InboundLine line : inbound) {
            if (gap == 0) break;
            int take = Math.min(line.qtyOutstanding(), gap);
            if (take <= 0) continue;
            allocations.add(new PurchaseOrderAllocation(line.purchaseOrderId(), line.poNumber(), take, line.expectedAt(), line.confidence()));
            gap -= take;
            fulfilledBy = line.expectedAt();
        }
        boolean fully = gap == 0;
        return new FulfillmentResponse(sku, fc, requested, requested - gap, gap, fromInventory,
                List.copyOf(allocations), fully, fully ? fulfilledBy : null);
    }
}
