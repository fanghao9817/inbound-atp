package com.haoyu.inboundsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DemandModelTest {

    @Test
    void anOrdinaryWeekAddsUpToTheConfiguredUnits() {
        Instant monday = Instant.parse("2026-10-12T07:00:00Z");      // a week without seasonal effects
        double orders = 0;
        for (int h = 0; h < 7 * 24; h++) {
            for (DemandModel.Fc fc : DemandModel.FCS) orders += DemandModel.ordersPerHour(675, fc, monday.plus(h, ChronoUnit.HOURS));
        }
        assertThat(orders * DemandModel.AVG_UNITS_PER_ORDER).isBetween(675 * 0.99, 675 * 1.01);
    }

    @Test
    void weightsAreNormalised() {
        assertThat(Arrays.stream(DemandModel.SKU_WEIGHT).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Arrays.stream(DemandModel.HOUR_WEIGHT).average().orElse(0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Arrays.stream(DemandModel.DOW).average().orElse(0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DemandModel.FCS.stream().mapToDouble(DemandModel.Fc::share).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void eveningsAndSundaysAreBusier() {
        DemandModel.Fc ric = DemandModel.FCS.getFirst();
        double tuesdayMorning = DemandModel.ordersPerHour(675, ric, Instant.parse("2026-10-13T16:00:00Z"));   // 09:00 PDT
        double tuesdayEvening = DemandModel.ordersPerHour(675, ric, Instant.parse("2026-10-14T03:00:00Z"));   // 20:00 PDT
        double sundayEvening = DemandModel.ordersPerHour(675, ric, Instant.parse("2026-10-12T03:00:00Z"));    // Sun 20:00 PDT
        assertThat(tuesdayEvening).isGreaterThan(tuesdayMorning * 2);
        assertThat(sundayEvening).isGreaterThan(tuesdayEvening);
    }

    @Test
    void longDeliveryDatesLoseCustomers() {
        assertThat(DemandModel.conversion(3)).isEqualTo(1.0);
        assertThat(DemandModel.conversion(20)).isEqualTo(0.7);
        assertThat(DemandModel.conversion(60)).isEqualTo(0.3);
    }
}
