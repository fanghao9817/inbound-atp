package com.haoyu.inbound.eta;

import static org.assertj.core.api.Assertions.assertThat;

import com.haoyu.inbound.eta.EtaPredictor.Confidence;
import com.haoyu.inbound.eta.EtaPredictor.Prediction;
import com.haoyu.inbound.procurement.MilestoneType;
import com.haoyu.inbound.procurement.Shipment;
import com.haoyu.inbound.procurement.ShipmentMilestone;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EtaPredictorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Shipment SHIPMENT = new Shipment(1, 1, "ONE", "ONEU0000001", TODAY.minusDays(10),
            TODAY.plusDays(15), null, null, null, MilestoneType.DEPARTED_ORIGIN, 0);

    private static ShipmentMilestone milestone(MilestoneType type, LocalDate on) {
        return new ShipmentMilestone(1, 1, type, on.atTime(9, 0).atOffset(ZoneOffset.UTC), "CARRIER_EDI", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static LaneStats stats(double p50, double p80, int n) {
        return new LaneStats("VNSGN", "FC-RIC", "DEPARTED_ORIGIN", "RECEIVED_FC", BigDecimal.valueOf(p50), BigDecimal.valueOf(p80), n);
    }

    @Test
    void noMilestoneFallsBackToCarrierPlanWithLowConfidence() {
        Prediction p = EtaPredictor.predict(SHIPMENT, Optional.empty(), Optional.empty(), "VNSGN→FC-RIC", TODAY, ZONE);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(15));
        assertThat(p.confidence()).isEqualTo(Confidence.LOW);
        assertThat(p.basis()).contains("carrier plan");
    }

    @Test
    void usesP80FromTheLatestMilestone() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.DEPARTED_ORIGIN, TODAY.minusDays(10))),
                Optional.of(stats(20.0, 23.4, 57)), "VNSGN→FC-RIC", TODAY, ZONE);
        assertThat(p.arrival()).isEqualTo(TODAY.minusDays(10).plusDays(24)); // ceil(23.4)
        assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);            // n>=30 but still at sea
        assertThat(p.basis()).contains("P80 of 57 shipments");
    }

    @Test
    void confidenceRisesOnceTheContainerHasArrivedAtThePort() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.ARRIVED_DEST_PORT, TODAY.minusDays(1))),
                Optional.of(stats(4.0, 6.0, 41)), "VNSGN→FC-RIC", TODAY, ZONE);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(5));
        assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void thinHistoryNeverGetsMoreThanMediumConfidence() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.ARRIVED_DEST_PORT, TODAY)),
                Optional.of(stats(4.0, 6.0, 12)), "VNSGN→FC-RIC", TODAY, ZONE);
        assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void overdueContainerIsReestimatedFromTodayWithLowConfidence() {
        // departed 40 days ago on a lane whose P80 is 23 days: it should have been in 17 days ago
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.DEPARTED_ORIGIN, TODAY.minusDays(40))),
                Optional.of(stats(20.0, 23.0, 57)), "VNSGN→FC-RIC", TODAY, ZONE);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(3));          // today + (P80 - P50)
        assertThat(p.confidence()).isEqualTo(Confidence.LOW);
        assertThat(p.basis()).startsWith("Overdue");
    }

    @Test
    void milestoneDateIsTheBusinessDayNotTheUtcDay() {
        // 23:56 in Vancouver on Sep 9 is already Sep 10 in UTC
        var lateEvening = new ShipmentMilestone(1, 1, MilestoneType.ARRIVED_DEST_PORT,
                OffsetDateTime.parse("2026-09-10T06:56:00Z"), "CARRIER_EDI", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        Prediction p = EtaPredictor.predict(SHIPMENT, Optional.of(lateEvening), Optional.of(stats(4.0, 6.0, 41)), "x",
                LocalDate.of(2026, 9, 9), ZoneId.of("America/Vancouver"));
        assertThat(p.arrival()).isEqualTo(LocalDate.of(2026, 9, 15));  // Sep 9 + 6
    }

    @Test
    void receivedIsFinal() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.RECEIVED_FC, TODAY.minusDays(2))), Optional.empty(), "x", TODAY, ZONE);
        assertThat(p.arrival()).isEqualTo(TODAY.minusDays(2));
        assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
    }
}
