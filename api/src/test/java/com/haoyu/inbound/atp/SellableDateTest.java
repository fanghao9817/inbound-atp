package com.haoyu.inbound.atp;

import static org.assertj.core.api.Assertions.assertThat;

import com.haoyu.inbound.catalog.FulfillmentCenter;
import com.haoyu.inbound.common.BusinessCalendar;
import com.haoyu.inbound.procurement.InboundSupply;
import com.haoyu.inbound.procurement.MilestoneType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SellableDateTest {

    static final FulfillmentCenter PAT = new FulfillmentCenter(1, "FC-PAT", "Patterson", "US-WEST", 3);
    static final FulfillmentCenter RIC = new FulfillmentCenter(2, "FC-RIC", "Richmond", "CA-WEST", 2);
    static final LocalDate SAT = LocalDate.of(2026, 10, 10);

    static InboundSupply arriving(LocalDate day) {
        return new InboundSupply(1, "PO-1", 1, 1, 1, 10, 0, day, null, null, MilestoneType.CUSTOMS_CLEARED, 1L);
    }

    @Test
    void dockToStockCountsWorkingDaysOnly() {
        assertThat(BusinessCalendar.addWorkingDays(SAT, 2)).isEqualTo(LocalDate.of(2026, 10, 13));   // Sat -> Mon, Tue
        assertThat(BusinessCalendar.nextWorkingDay(SAT)).isEqualTo(LocalDate.of(2026, 10, 12));      // Sunday is closed
        assertThat(AtpService.sellableFrom(arriving(SAT), RIC, SAT.minusDays(7))).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void stockDueToBePutAwayTodayIsNotSellableNowButStillKeepsTodaysPromise() {
        LocalDate today = LocalDate.of(2026, 10, 14);                                   // Wednesday
        InboundSupply dueToday = arriving(LocalDate.of(2026, 10, 10));                   // Sat + 3 working days = Wed
        assertThat(AtpService.sellableFrom(dueToday, PAT, today)).isEqualTo(today.plusDays(1));    // not in the building yet
        assertThat(AtpService.expectedSellable(dueToday, PAT, today)).isEqualTo(today);            // but expected later today
    }

    @Test
    void overdueStockMovesToTheNextWorkingDayEitherWay() {
        LocalDate saturday = SAT;
        InboundSupply overdue = arriving(LocalDate.of(2026, 10, 1));
        assertThat(AtpService.sellableFrom(overdue, PAT, saturday)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(AtpService.expectedSellable(overdue, PAT, saturday)).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
