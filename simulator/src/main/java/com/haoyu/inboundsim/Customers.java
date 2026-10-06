package com.haoyu.inboundsim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Online shoppers (every minute) and B2B buyers (weekday mornings).
 *
 * <p>Online: for each FC and UTC hour the number of orders is Poisson(DemandModel.ordersPerHour) and
 * each order's minute, SKU and quantity are hashed from (seed, FC, hour, i). A shopper first asks the
 * API when it could be delivered and buys with the probability DemandModel.conversion(lead); if no
 * date can be promised at all the order is placed anyway and the API records it as REJECTED - lost
 * demand. Order refs are WEB-{FC}-{yyyyMMddHH}-{i}: a restart replays the same refs and the API's
 * idempotency makes that harmless. Orders whose minute is more than an hour old are dropped (a
 * customer does not wait for a restarted website).
 */
@Component
class Customers {

    private static final Logger log = LoggerFactory.getLogger(Customers.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(java.time.ZoneOffset.UTC);
    private static final ZoneId HQ = ZoneId.of("America/Vancouver");

    private final ApiClient api;
    private final SimProperties props;
    private final Clock clock;
    private final Heartbeat heartbeat;
    private final Set<String> done = Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 20_000;
        }
    });

    Customers(ApiClient api, SimProperties props, Clock clock, Heartbeat heartbeat) {
        this.api = api;
        this.props = props;
        this.clock = clock;
        this.heartbeat = heartbeat;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void online() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        Instant thisHour = now.truncatedTo(ChronoUnit.HOURS);
        int placed = 0;
        try {
            for (Instant hour : new Instant[] {thisHour.minus(Duration.ofHours(1)), thisHour}) {
                for (DemandModel.Fc fc : DemandModel.FCS) {
                    double lambda = DemandModel.ordersPerHour(props.baseWeeklyUnits(), fc, hour);
                    int n = Rng.poisson(Rng.of(props.seed(), "web", fc.code(), hour), lambda);
                    for (int i = 0; i < n; i++) {
                        String ref = "WEB-" + fc.code().substring(3) + "-" + HOUR.format(hour) + "-" + i;
                        if (done.contains(ref)) continue;
                        SplittableRandom r = Rng.of(props.seed(), "web", fc.code(), hour, i);
                        Instant at = hour.plusSeconds(r.nextInt(3600));
                        if (at.isAfter(now)) continue;
                        done.add(ref);
                        if (at.isBefore(now.minus(Duration.ofHours(1)))) continue;
                        String sku = DemandModel.pickSku(r);
                        int qty = DemandModel.pickQty(sku, r);
                        if (buys(sku, fc, qty, r)) {
                            api.order(ref, "ONLINE", sku, fc.code(), qty, null);
                            placed++;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("online customers tick failed: {}", e.toString());
        }
        heartbeat.beat();
        if (placed > 0) log.debug("online customers: {} orders placed", placed);
    }

    private boolean buys(String sku, DemandModel.Fc fc, int qty, SplittableRandom r) {
        ApiClient.Quote q = api.quote(sku, fc.code(), qty);
        if (!q.promisable()) return true;                       // placed anyway: the API records the lost demand
        long lead = ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(fc.zone())), q.promiseDate());
        return r.nextDouble() < DemandModel.conversion(lead);
    }

    /**
     * B2B: Poisson(1) orders per weekday, placed between 08:00 and 12:00 Vancouver time, 5-20 units,
     * wanted 3-10 weeks out (so most become SCHEDULED demand).
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    void b2b() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(HQ));
        if (!DemandModel.isWeekday(today.getDayOfWeek())) return;
        int n = Rng.poisson(Rng.of(props.seed(), "b2b", today), 1.0);
        try {
            for (int i = 0; i < n; i++) {
                String ref = "B2B-" + today.toString().replace("-", "") + "-" + i;
                if (done.contains(ref)) continue;
                SplittableRandom r = Rng.of(props.seed(), "b2b", today, i);
                Instant at = today.atTime(LocalTime.of(8, 0)).plusMinutes(r.nextInt(240)).atZone(HQ).toInstant();
                if (at.isAfter(now)) continue;
                done.add(ref);
                if (at.isBefore(now.minus(Duration.ofHours(1)))) continue;
                String sku = DemandModel.pickSku(r);
                DemandModel.Fc fc = DemandModel.pickFc(r);
                api.order(ref, "B2B", sku, fc.code(), 5 + r.nextInt(16), today.plusDays(21 + r.nextInt(50)));
                log.info("B2B order {} placed", ref);
            }
        } catch (RuntimeException e) {
            log.warn("b2b tick failed: {}", e.toString());
        }
    }
}
