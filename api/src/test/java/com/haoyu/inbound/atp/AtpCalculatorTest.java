package com.haoyu.inbound.atp;

import static org.assertj.core.api.Assertions.assertThat;

import com.haoyu.inbound.atp.AtpCalculator.Demand;
import com.haoyu.inbound.atp.AtpCalculator.Result;
import com.haoyu.inbound.atp.AtpCalculator.Supply;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AtpCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    @Test
    void stockOnHandIsPromisableToday() {
        Result r = AtpCalculator.compute(TODAY, 5, List.of(), List.of(), 60);
        assertThat(r.earliestDateFor(5)).contains(TODAY);
        assertThat(r.earliestDateFor(6)).isEmpty();
    }

    @Test
    void inboundSupplyMovesThePromiseDateOut() {
        Result r = AtpCalculator.compute(TODAY, 2,
                List.of(new Supply(TODAY.plusDays(14), 10, "PO-1")), List.of(), 60);
        assertThat(r.earliestDateFor(2)).contains(TODAY);
        assertThat(r.earliestDateFor(3)).contains(TODAY.plusDays(14));
        assertThat(r.earliestDateFor(12)).contains(TODAY.plusDays(14));
        assertThat(r.earliestDateFor(13)).isEmpty();
    }

    @Test
    void lookAheadPreventsPromisingStockThatLaterDemandNeeds() {
        // 10 on hand, but 8 are due to a B2B customer in 20 days: only 2 are truly free today
        Result r = AtpCalculator.compute(TODAY, 10, List.of(),
                List.of(new Demand(TODAY.plusDays(20), 8, "B2B-77")), 60);
        assertThat(r.timeline().getFirst().projected()).isEqualTo(10);
        assertThat(r.timeline().getFirst().atp()).isEqualTo(2);
        assertThat(r.earliestDateFor(3)).isEmpty();
    }

    @Test
    void supplyArrivingBeforeDemandRestoresPromisability() {
        Result r = AtpCalculator.compute(TODAY, 10,
                List.of(new Supply(TODAY.plusDays(10), 8, "PO-2")),
                List.of(new Demand(TODAY.plusDays(20), 8, "B2B-77")), 60);
        // the inbound 8 covers the commitment, so all 10 on hand are free today
        assertThat(r.earliestDateFor(10)).contains(TODAY);
        assertThat(r.earliestDateFor(11)).isEmpty();
    }

    @Test
    void pastDatesCollapseOntoTodayAndBeyondHorizonIsIgnored() {
        Result r = AtpCalculator.compute(TODAY, 0,
                List.of(new Supply(TODAY.minusDays(3), 4, "late-PO"),
                        new Supply(TODAY.plusDays(500), 100, "far-PO")),
                List.of(), 60);
        assertThat(r.timeline()).hasSize(1);
        assertThat(r.earliestDateFor(4)).contains(TODAY);
        assertThat(r.earliestDateFor(5)).isEmpty();
    }

    @Test
    void atpNeverGoesNegative() {
        Result r = AtpCalculator.compute(TODAY, 1, List.of(),
                List.of(new Demand(TODAY.plusDays(1), 5, "over-committed")), 60);
        assertThat(r.timeline()).allSatisfy(p -> assertThat(p.atp()).isGreaterThanOrEqualTo(0));
        assertThat(r.timeline().get(1).projected()).isEqualTo(-4);
    }
}
