package com.haoyu.inboundsim;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pick, pack and ship (every 15 minutes) and customer cancellations (hourly).
 *
 * <p>Each FC ships reserved orders Monday to Saturday 07:00-19:00 local time; orders wait at least two
 * hours after reservation (picking), and B2B orders are not shipped before the day before they are
 * wanted. Sunday is closed, which is why Monday starts with a backlog.
 *
 * <p>Cancellations are a hazard, decided per order per hour from the seed: a reserved order cancels
 * with probability 1% over its first 24 hours, a backordered one 0.5% per day while it waits.
 */
@Component
class Warehouse {

    private static final Logger log = LoggerFactory.getLogger(Warehouse.class);
    private static final Map<String, DemandModel.Fc> FC = DemandModel.FCS.stream().collect(Collectors.toMap(DemandModel.Fc::code, f -> f));

    private final ApiClient api;
    private final SimProperties props;
    private final Clock clock;

    Warehouse(ApiClient api, SimProperties props, Clock clock) {
        this.api = api;
        this.props = props;
        this.clock = clock;
    }

    static boolean open(ZonedDateTime local) {
        return local.getDayOfWeek() != DayOfWeek.SUNDAY && local.getHour() >= 7 && local.getHour() < 19;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    void ship() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        int shipped = 0;
        try {
            for (ApiClient.Order o : api.orders("RESERVED")) {
                DemandModel.Fc fc = FC.get(o.fc());
                if (fc == null || "VISITOR".equals(o.origin())) continue;          // visitor orders are never shipped
                ZonedDateTime local = now.atZone(fc.zone());
                if (!open(local)) continue;
                if (o.reservedAt() == null || o.reservedAt().toInstant().isAfter(now.minus(Duration.ofHours(2)))) continue;
                if (o.needBy() != null && o.needBy().isAfter(local.toLocalDate().plusDays(1))) continue;
                if (api.ship(o.id()) < 300) shipped++;
            }
        } catch (RuntimeException e) {
            log.warn("warehouse tick failed: {}", e.toString());
        }
        if (shipped > 0) log.info("warehouse: {} orders shipped", shipped);
    }

    @Scheduled(cron = "0 7 * * * *")
    void cancellations() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        int cancelled = 0;
        try {
            for (String status : new String[] {"RESERVED", "BACKORDERED"}) {
                for (ApiClient.Order o : api.orders(status)) {
                    if (!"FEED".equals(o.origin())) continue;
                    double p = status.equals("RESERVED")
                            ? (o.createdAt().toInstant().isAfter(now.minus(Duration.ofHours(24))) ? 0.01 / 24 : 0)
                            : 0.005 / 24;
                    if (p > 0 && Rng.of(props.seed(), "cancel", o.orderRef(), hour).nextDouble() < p) {
                        if (api.cancel(o.id()) < 300) cancelled++;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("cancellation tick failed: {}", e.toString());
        }
        if (cancelled > 0) log.info("customers cancelled {} orders", cancelled);
    }
}
