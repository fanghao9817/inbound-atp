package com.haoyu.inbound.planning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplenishmentServiceTest {

    @Test
    void ordersUpToTargetInCasePacksWhenCoverIsBelowTheReorderPoint() {
        // 40 units in 28 days = 10/week; position 30 + 20 - 0 = 50 -> 5 weeks of cover < 8
        var s = ReplenishmentService.evaluate("SKU", "FC", 40, 30, 20, 0, 8, 14);
        assertThat(s.weeklyDemand()).isEqualTo(10.0);
        assertThat(s.weeksOfCover()).isEqualTo(5.0);
        assertThat(s.suggestedQty()).isEqualTo(90);   // 14 x 10 - 50 = 90
    }

    @Test
    void enoughCoverMeansNoOrder() {
        var s = ReplenishmentService.evaluate("SKU", "FC", 40, 60, 40, 0, 8, 14);
        assertThat(s.weeksOfCover()).isEqualTo(10.0);
        assertThat(s.suggestedQty()).isZero();
    }

    @Test
    void backordersReduceThePosition() {
        var s = ReplenishmentService.evaluate("SKU", "FC", 40, 30, 20, 25, 8, 14);
        assertThat(s.position()).isEqualTo(25);
        assertThat(s.suggestedQty()).isEqualTo(120);  // 140 - 25 = 115 -> 120
    }

    @Test
    void noDemandMeansNoOrderAndInfiniteCover() {
        var s = ReplenishmentService.evaluate("SKU", "FC", 0, 0, 0, 0, 8, 14);
        assertThat(s.suggestedQty()).isZero();
        assertThat(s.weeksOfCover()).isEqualTo(999);
    }
}
