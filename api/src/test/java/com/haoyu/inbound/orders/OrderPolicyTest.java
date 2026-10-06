package com.haoyu.inbound.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.haoyu.inbound.orders.OrderPolicy.Action;
import com.haoyu.inbound.orders.OrderPolicy.Decision;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final int WINDOW = 2;

    @Test
    void coverableTodayAndWantedNowIsReserved() {
        Decision d = OrderPolicy.decide(null, TODAY, Optional.of(TODAY), WINDOW);
        assertThat(d).isEqualTo(new Decision(Action.RESERVE, TODAY));
    }

    @Test
    void coverableOnlyLaterIsBackorderedAtThatDate() {
        Decision d = OrderPolicy.decide(null, TODAY, Optional.of(TODAY.plusDays(17)), WINDOW);
        assertThat(d).isEqualTo(new Decision(Action.BACKORDER, TODAY.plusDays(17)));
    }

    @Test
    void notCoverableWithinTheHorizonIsRejected() {
        assertThat(OrderPolicy.decide(null, TODAY, Optional.empty(), WINDOW).action()).isEqualTo(Action.REJECT);
    }

    @Test
    void b2bNeededInAMonthIsScheduledAndLocksNoStockToday() {
        Decision d = OrderPolicy.decide(TODAY.plusDays(30), TODAY, Optional.of(TODAY), WINDOW);
        assertThat(d).isEqualTo(new Decision(Action.SCHEDULE, TODAY.plusDays(30)));
    }

    @Test
    void b2bNeededInAMonthButCoverableOnlyLaterIsBackorderedAtTheLaterDate() {
        Decision d = OrderPolicy.decide(TODAY.plusDays(30), TODAY, Optional.of(TODAY.plusDays(45)), WINDOW);
        assertThat(d).isEqualTo(new Decision(Action.BACKORDER, TODAY.plusDays(45)));
    }

    @Test
    void b2bNeededWithinTheWindowIsReservedNow() {
        Decision d = OrderPolicy.decide(TODAY.plusDays(WINDOW), TODAY, Optional.of(TODAY), WINDOW);
        assertThat(d.action()).isEqualTo(Action.RESERVE);
    }

    @Test
    void b2bWantedBeforeStockArrivesIsPromisedAtArrivalNotAtItsWish() {
        Decision d = OrderPolicy.decide(TODAY.plusDays(5), TODAY, Optional.of(TODAY.plusDays(20)), WINDOW);
        assertThat(d).isEqualTo(new Decision(Action.BACKORDER, TODAY.plusDays(20)));
    }
}
