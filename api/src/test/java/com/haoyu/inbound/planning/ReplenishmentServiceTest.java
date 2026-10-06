package com.haoyu.inbound.planning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplenishmentServiceTest {

    @Test
    void atGoLiveTheForecastIsThePrior() {
        // no history yet: f = prior 14/week; L = 42 d = 6 weeks -> S = 14 x (6 + 1 + 2) = 126
        var l = ReplenishmentService.evaluate("SKU", "FC", "VNSGN", "S", 0, 0, 14, 42, 30, 20, 0);
        assertThat(l.weeklyDemand()).isEqualTo(14.0);
        assertThat(l.orderUpTo()).isEqualTo(126);
        assertThat(l.suggestedQty()).isEqualTo(80);          // 126 - 50 = 76 -> case pack of 5 -> 80
    }

    @Test
    void afterFourWeeksTheObservedRateTakesOver() {
        // 80 units in 28 days = 20/week, prior ignored
        var l = ReplenishmentService.evaluate("SKU", "FC", "VNSGN", "S", 80, 28, 14, 42, 30, 20, 0);
        assertThat(l.weeklyDemand()).isEqualTo(20.0);
        assertThat(l.orderUpTo()).isEqualTo(180);
    }

    @Test
    void halfwayTheyBlend() {
        // 14 days observed, 40 units -> 20/week observed; weight 0.5 -> (20 + 14) / 2 = 17
        var l = ReplenishmentService.evaluate("SKU", "FC", "VNSGN", "S", 40, 14, 14, 42, 0, 0, 0);
        assertThat(l.weeklyDemand()).isEqualTo(17.0);
    }

    @Test
    void commitmentsReducePositionAndSmallNeedsAreNotOrdered() {
        var l = ReplenishmentService.evaluate("SKU", "FC", "VNSGN", "S", 0, 0, 14, 42, 100, 60, 40);
        assertThat(l.position()).isEqualTo(120);
        assertThat(l.suggestedQty()).isZero();               // 126 - 120 = 6 < minimum order of 10
    }
}
