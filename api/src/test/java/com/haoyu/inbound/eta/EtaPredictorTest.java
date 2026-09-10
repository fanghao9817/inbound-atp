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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EtaPredictorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
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
        Prediction p = EtaPredictor.predict(SHIPMENT, Optional.empty(), Optional.empty(), "VNSGN→FC-RIC", TODAY);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(15));
        assertThat(p.confidence()).isEqualTo(Confidence.LOW);
        assertThat(p.basis()).contains("carrier plan");
    }

    @Test
    void usesP80FromTheLatestMilestone() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.DEPARTED_ORIGIN, TODAY.minusDays(10))),
                Optional.of(stats(20.0, 23.4, 57)), "VNSGN→FC-RIC", TODAY);
        assertThat(p.arrival()).isEqualTo(TODAY.minusDays(10).plusDays(24)); // ceil(23.4)
        assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);            // n>=30 but still at sea
        assertThat(p.basis()).contains("P80 of 57 shipments");
    }

    @Test
    void confidenceRisesOnceTheContainerHasArrivedAtThePort() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.ARRIVED_DEST_PORT, TODAY.minusDays(1))),
                Optional.of(stats(4.0, 6.0, 41)), "VNSGN→FC-RIC", TODAY);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(5));
        assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void thinHistoryNeverGetsMoreThanMediumConfidence() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.ARRIVED_DEST_PORT, TODAY)),
                Optional.of(stats(4.0, 6.0, 12)), "VNSGN→FC-RIC", TODAY);
        assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void overdueContainerIsNeverPredictedInThePast() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.DEPARTED_ORIGIN, TODAY.minusDays(40))),
                Optional.of(stats(20.0, 23.0, 57)), "VNSGN→FC-RIC", TODAY);
        assertThat(p.arrival()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void receivedIsFinal() {
        Prediction p = EtaPredictor.predict(SHIPMENT,
                Optional.of(milestone(MilestoneType.RECEIVED_FC, TODAY.minusDays(2))), Optional.empty(), "x", TODAY);
        assertThat(p.arrival()).isEqualTo(TODAY.minusDays(2));
        assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
    }
}
