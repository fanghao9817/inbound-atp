package com.haoyu.inbound.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllocationPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final List<InboundLine> INBOUND = List.of(
            new InboundLine(1, "PO-1", 50, TODAY.plusDays(10), "HIGH"),
            new InboundLine(2, "PO-2", 40, TODAY.plusDays(20), "MEDIUM"),
            new InboundLine(3, "PO-3", 100, TODAY.plusDays(30), "LOW"));

    @Test
    void inventoryAloneCoversTheRequest() {
        FulfillmentResponse r = AllocationPlanner.plan("SKU", null, 30, 45, INBOUND, TODAY);
        assertThat(r.allocatedFromInventory()).isEqualTo(30);
        assertThat(r.purchaseOrderAllocations()).isEmpty();
        assertThat(r.fullyFulfilled()).isTrue();
        assertThat(r.fulfilledBy()).isEqualTo(TODAY);
    }

    @Test
    void gapIsFilledFromPurchaseOrdersInArrivalOrderTakingOnlyWhatIsNeeded() {
        FulfillmentResponse r = AllocationPlanner.plan("SKU", "FC-RIC", 120, 45, INBOUND, TODAY);
        assertThat(r.allocatedFromInventory()).isEqualTo(45);
        assertThat(r.purchaseOrderAllocations()).extracting(PurchaseOrderAllocation::poNumber, PurchaseOrderAllocation::allocatedQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PO-1", 50), org.assertj.core.groups.Tuple.tuple("PO-2", 25));
        assertThat(r.fulfilledQuantity()).isEqualTo(120);
        assertThat(r.remainingQuantity()).isZero();
        assertThat(r.fullyFulfilled()).isTrue();
        assertThat(r.fulfilledBy()).isEqualTo(TODAY.plusDays(20));
    }

    @Test
    void shortfallIsReportedWhenEvenAllInboundIsNotEnough() {
        FulfillmentResponse r = AllocationPlanner.plan("SKU", null, 300, 45, INBOUND, TODAY);
        assertThat(r.fulfilledQuantity()).isEqualTo(45 + 50 + 40 + 100);
        assertThat(r.remainingQuantity()).isEqualTo(300 - 235);
        assertThat(r.fullyFulfilled()).isFalse();
        assertThat(r.fulfilledBy()).isNull();
    }

    @Test
    void negativeInventoryIsTreatedAsZero() {
        FulfillmentResponse r = AllocationPlanner.plan("SKU", null, 10, -5, INBOUND, TODAY);
        assertThat(r.allocatedFromInventory()).isZero();
        assertThat(r.purchaseOrderAllocations()).hasSize(1);
    }
}
