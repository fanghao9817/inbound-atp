package com.haoyu.inboundsim;

import static org.assertj.core.api.Assertions.assertThat;

import com.haoyu.inboundsim.TransitModel.Container;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TransitModelTest {

    static final Container C = new Container("PO-2001", "VNSGN", "FC-RIC");
    static final Instant T0 = Instant.parse("2026-10-06T18:00:00Z");

    @Test
    void theSameInputsAlwaysGiveTheSameAnswer() {
        for (Stage s : new Stage[] {Stage.BOOKED, Stage.DEPARTED_ORIGIN, Stage.ARRIVED_DEST_PORT, Stage.CUSTOMS_CLEARED}) {
            assertThat(TransitModel.nextStageTime("seed", C, s, T0, 0)).isEqualTo(TransitModel.nextStageTime("seed", C, s, T0, 0));
            assertThat(TransitModel.nextStageTime("seed", C, s, T0, 0)).isAfter(T0);
        }
        assertThat(TransitModel.nextStageTime("other-seed", C, Stage.DEPARTED_ORIGIN, T0, 0))
                .isNotEqualTo(TransitModel.nextStageTime("seed", C, Stage.DEPARTED_ORIGIN, T0, 0));
    }

    @Test
    void oceanLegFollowsTheLaneMedian() {
        // over many containers the departed -> port leg is about 0.78 x 24 days on VNSGN -> FC-RIC
        double total = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            Instant t = TransitModel.nextStageTime("seed", new Container("PO-" + i, "VNSGN", "FC-RIC"), Stage.DEPARTED_ORIGIN, T0, 0);
            total += Duration.between(T0, t).toHours() / 24.0;
        }
        assertThat(total / n).isBetween(19.0, 22.5);   // 18.7 median plus the 10% delayed tail
    }

    @Test
    void containersReachTheDockOnlyMondayToSaturdayInDayShift() {
        for (int i = 0; i < 300; i++) {
            Instant t = TransitModel.nextStageTime("seed", new Container("PO-" + i, "CNSHA", "FC-JAX"), Stage.CUSTOMS_CLEARED, T0, 0);
            ZonedDateTime local = t.atZone(ZoneId.of("America/New_York"));
            assertThat(local.getDayOfWeek()).isNotEqualTo(DayOfWeek.SUNDAY);
            assertThat(local.getHour()).isBetween(7, 14);
        }
    }

    @Test
    void goodsReceiptSkipsSundays() {
        // gate-in on Saturday 2026-10-10 at Richmond with 2 dock-to-stock days -> Monday, then Tuesday
        Instant gateIn = ZonedDateTime.of(2026, 10, 10, 9, 0, 0, 0, ZoneId.of("America/Vancouver")).toInstant();
        LocalDate grn = TransitModel.goodsReceiptTime("seed", C, gateIn, 2).atZone(ZoneId.of("America/Vancouver")).toLocalDate();
        assertThat(grn).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void congestionHitsAboutOneWeekInTwelvePerGateway() {
        int congested = 0, weeks = 520;
        LocalDate d = LocalDate.of(2026, 1, 5);
        for (int w = 0; w < weeks; w++) if (TransitModel.congestionDays("seed", "CAVAN", d.plusWeeks(w)) > 0) congested++;
        assertThat(congested / (double) weeks).isBetween(0.04, 0.13);
    }
}
