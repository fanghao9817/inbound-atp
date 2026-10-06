package com.haoyu.inboundsim;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The buyer: every Monday at 08:00 Vancouver time it takes the API's replenishment proposals and places
 * them as purchase orders, sailing on the next weekly departure 5-10 days out. It also runs once at
 * start-up; the proposals' clientRef (REPL-{week}-{origin}-{FC}) makes a second submission in the same
 * week a no-op. And it relays port-congestion notices (one per gateway per congested week).
 */
@Component
class Buyer {

    private static final Logger log = LoggerFactory.getLogger(Buyer.class);
    private static final ZoneId HQ = ZoneId.of("America/Vancouver");
    private static final String[] CARRIERS = {"Maersk", "ONE", "CMA CGM", "Evergreen"};

    private final ApiClient api;
    private final SimProperties props;
    private final Clock clock;

    Buyer(ApiClient api, SimProperties props, Clock clock) {
        this.api = api;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void atStartup() {
        replenish();
        congestionNotices();
    }

    @Scheduled(cron = "0 0 8 * * MON", zone = "America/Vancouver")
    void replenish() {
        if (!props.enabled()) return;
        try {
            LocalDate today = LocalDate.now(clock.withZone(HQ));
            int placed = 0;
            for (ApiClient.Proposal p : api.replenishment().proposals()) {
                SplittableRandom r = Rng.of(props.seed(), "sailing", p.clientRef());
                int status = api.purchaseOrder(p, today.plusDays(5 + r.nextInt(6)), CARRIERS[r.nextInt(CARRIERS.length)]);
                if (status == 201) placed++;
            }
            log.info("buyer: {} new purchase orders placed", placed);
        } catch (RuntimeException e) {
            log.warn("buyer run failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 15 * * * *")
    void congestionNotices() {
        if (!props.enabled()) return;
        try {
            for (String gw : TransitModel.GATEWAY_NAME.keySet()) {
                LocalDate day = ZonedDateTime.now(clock.withZone(TransitModel.GATEWAY_ZONE.get(gw))).toLocalDate();
                int extra = TransitModel.congestionDays(props.seed(), gw, day);
                if (extra > 0) {
                    String week = day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "W" + day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    api.note("congestion-" + gw + "-" + week, "PORT",
                            "Port congestion at %s (simulated): containers arriving this week clear customs about %d days late"
                                    .formatted(TransitModel.GATEWAY_NAME.get(gw), extra));
                }
            }
        } catch (RuntimeException e) {
            log.warn("congestion notices failed: {}", e.toString());
        }
    }
}
