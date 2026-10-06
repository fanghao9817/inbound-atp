package com.haoyu.inboundsim;

import com.haoyu.inboundsim.SimulatorApplication.GoLive;
import com.haoyu.inboundsim.TransitModel.Container;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Carriers, customs brokers and the warehouse dock. Every five minutes, for each open container: work
 * out when its next stage truly happens (TransitModel) and, if that moment has passed, report it - with
 * its true time, so a report that is late because the simulator was down is simply late EDI. After the
 * gate-in (RECEIVED_FC) the warehouse posts the goods receipt once the dock-to-stock days are over.
 *
 * <p>Containers that were already at sea when the simulator went live have their next stage drawn
 * conditional on "not before go-live" (rejection sampling over salts), so the existing book does not
 * all land at once. Only if that fails 20 times is the event spread over the two days after go-live
 * and marked CARRIER_EDI_RECOVERY, which keeps it out of lane statistics and accuracy scoring.
 */
@Component
class CarrierFeed {

    private static final Logger log = LoggerFactory.getLogger(CarrierFeed.class);

    record Next(Instant at, boolean recovery) {}

    private final ApiClient api;
    private final SimProperties props;
    private final GoLive goLive;
    private final Clock clock;
    private final Heartbeat heartbeat;

    CarrierFeed(ApiClient api, SimProperties props, GoLive goLive, Clock clock, Heartbeat heartbeat) {
        this.api = api;
        this.props = props;
        this.goLive = goLive;
        this.clock = clock;
        this.heartbeat = heartbeat;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 20_000)
    void tick() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        int reported = 0, received = 0;
        try {
            for (ApiClient.PurchaseOrder po : api.openPurchaseOrders()) {
                if (po.shipmentId() == null) continue;
                Container c = new Container(po.poNumber(), po.originPort(), po.destFc());
                ApiClient.ShipmentView view = api.shipment(po.shipmentId());
                ApiClient.Milestone last = view.milestones().stream()
                        .max(Comparator.comparing((ApiClient.Milestone m) -> Stage.valueOf(m.type()).ordinal())).orElse(null);
                if (last == null) continue;                                  // not even booked: nothing to report yet
                Stage stage = Stage.valueOf(last.type());
                Instant at = last.occurredAt().toInstant();
                Instant recorded = last.recordedAt().toInstant();
                while (stage != Stage.RECEIVED_FC) {
                    Next next = next(c, stage, at, recorded);
                    if (next.at().isAfter(now)) break;
                    Stage s = stage.next();
                    int status = api.milestone(po.shipmentId(), s, next.at(), next.recovery() ? "CARRIER_EDI_RECOVERY" : s.source(),
                            eventId(po.poNumber(), s.name()));
                    if (status >= 300) {
                        log.warn("{} {} refused with {}", po.poNumber(), s, status);
                        break;
                    }
                    reported++;
                    stage = s;
                    at = next.at();
                    recorded = now;
                }
                if (stage == Stage.RECEIVED_FC && receiveIfDue(po, view, c, at, now)) received++;
            }
        } catch (RuntimeException e) {
            log.warn("carrier feed tick failed: {}", e.toString());
        }
        heartbeat.beat();
        if (reported + received > 0) log.info("carrier feed: {} milestones reported, {} containers put away", reported, received);
    }

    Next next(Container c, Stage stage, Instant at, Instant recorded) {
        Instant live = goLive.at();
        if (!recorded.isBefore(live)) {
            return new Next(TransitModel.nextStageTime(props.seed(), c, stage, at, 0), false);
        }
        for (int salt = 0; salt < 20; salt++) {
            Instant t = TransitModel.nextStageTime(props.seed(), c, stage, at, salt);
            if (t.isAfter(live)) return new Next(t, false);
        }
        SplittableRandom r = Rng.of(props.seed(), "recovery", c.poNumber(), stage);
        return new Next(live.plus(Duration.ofMinutes(60 + r.nextInt(47 * 60))), true);
    }

    private boolean receiveIfDue(ApiClient.PurchaseOrder po, ApiClient.ShipmentView view, Container c, Instant gateIn, Instant now) {
        Instant grnAt = TransitModel.goodsReceiptTime(props.seed(), c, gateIn, view.lane().receivingBufferDays());
        if (grnAt.isAfter(now)) return false;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ApiClient.Line l : po.lines()) {
            int outstanding = l.qtyOrdered() - l.qtyReceived();
            if (outstanding <= 0) continue;
            SplittableRandom r = Rng.of(props.seed(), "count", po.poNumber(), l.skuCode());
            int shortBy = r.nextDouble() < 0.02 ? 1 + r.nextInt(5) : 0;              // supplier short-shipped the line
            int received = Math.max(0, outstanding - shortBy);
            int damaged = Rng.binomial(r, received, 0.01);                            // ~1% damaged in transit
            lines.add(Map.of("sku", l.skuCode(), "qtyReceived", received, "qtyDamaged", damaged));
        }
        int status = api.receipt(po.shipmentId(), eventId(po.poNumber(), "GRN"), grnAt, lines);
        if (status >= 300 && status != 409) log.warn("{} goods receipt refused with {}", po.poNumber(), status);
        return status < 300;
    }

    static UUID eventId(String poNumber, String what) {
        return UUID.nameUUIDFromBytes(("sim:" + poNumber + ":" + what).getBytes(StandardCharsets.UTF_8));
    }
}
