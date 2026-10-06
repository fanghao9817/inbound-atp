package com.haoyu.inboundsim;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * How long a container really takes, stage by stage. Pure and deterministic: the same seed, purchase
 * order and stage always give the same answer. The distributions are the ones the API's demo seeder
 * used to generate a year of history, so live behaviour matches the lane statistics dbt learned:
 * <pre>
 *   BOOKED -> DEPARTED_ORIGIN           3-7 days
 *   DEPARTED -> ARRIVED_DEST_PORT       0.78 x lane median x lognormal(0, 0.15); 10% stuck 5-12 extra days
 *   ARRIVED -> CUSTOMS_CLEARED          1-4 days, + 7-14 days if the destination port is congested that week
 *   CUSTOMS -> RECEIVED_FC (gate-in)    2-6 days of drayage, then the next dock slot (Mon-Sat 07:00-15:00 FC time)
 *   gate-in -> goods receipt            the FC's dock-to-stock working days, put away 08:00-16:00
 * </pre>
 */
public final class TransitModel {

    /** Typical DEPARTED_ORIGIN -> RECEIVED_FC transit in days, per lane (identical to the API's seeder). */
    static final Map<String, Double> MEDIAN_DAYS = Map.ofEntries(
            Map.entry("VNSGN|FC-RIC", 24.0), Map.entry("VNSGN|FC-CAL", 30.0), Map.entry("VNSGN|FC-JAX", 40.0), Map.entry("VNSGN|FC-PAT", 26.0),
            Map.entry("CNSHA|FC-RIC", 18.0), Map.entry("CNSHA|FC-CAL", 24.0), Map.entry("CNSHA|FC-JAX", 36.0), Map.entry("CNSHA|FC-PAT", 20.0),
            Map.entry("MYPKG|FC-RIC", 27.0), Map.entry("MYPKG|FC-CAL", 33.0), Map.entry("MYPKG|FC-JAX", 42.0), Map.entry("MYPKG|FC-PAT", 29.0));

    /** Destination gateway port per FC: Vancouver serves Richmond and Calgary. */
    static final Map<String, String> GATEWAY = Map.of("FC-RIC", "CAVAN", "FC-CAL", "CAVAN", "FC-PAT", "USOAK", "FC-JAX", "USJAX");
    static final Map<String, String> GATEWAY_NAME = Map.of("CAVAN", "Vancouver", "USOAK", "Oakland", "USJAX", "Jacksonville");
    static final Map<String, ZoneId> GATEWAY_ZONE = Map.of("CAVAN", ZoneId.of("America/Vancouver"),
            "USOAK", ZoneId.of("America/Los_Angeles"), "USJAX", ZoneId.of("America/New_York"));
    static final Map<String, ZoneId> FC_ZONE = Map.of("FC-RIC", ZoneId.of("America/Vancouver"), "FC-CAL", ZoneId.of("America/Edmonton"),
            "FC-PAT", ZoneId.of("America/Los_Angeles"), "FC-JAX", ZoneId.of("America/New_York"));

    static final double CONGESTION_WEEKLY_PROBABILITY = 0.08;

    public record Container(String poNumber, String origin, String fc) {}

    private TransitModel() {}

    /**
     * When the stage after {@code from} happens, given when {@code from} happened. {@code salt} selects
     * an alternative draw; the feed uses it to condition on "has not happened before go-live" for
     * containers that were already at sea when the simulator started.
     */
    public static Instant nextStageTime(String seed, Container c, Stage from, Instant fromTime, int salt) {
        SplittableRandom r = Rng.of(seed, "transit", c.poNumber(), from, salt);
        return switch (from) {
            case BOOKED -> plusDays(fromTime, 3 + r.nextInt(5), r);
            case DEPARTED_ORIGIN -> {
                double transit = MEDIAN_DAYS.getOrDefault(c.origin() + "|" + c.fc(), 30.0) * Math.exp(Rng.gaussian(r) * 0.15);
                int days = (int) Math.max(1, Math.round(transit * 0.78));
                if (r.nextDouble() < 0.10) days += 5 + r.nextInt(8);
                yield plusDays(fromTime, days, r);
            }
            case ARRIVED_DEST_PORT -> {
                String gw = GATEWAY.getOrDefault(c.fc(), "CAVAN");
                LocalDate arrival = fromTime.atZone(GATEWAY_ZONE.get(gw)).toLocalDate();
                int days = 1 + r.nextInt(4) + congestionDays(seed, gw, arrival);
                yield plusDays(fromTime, days, r);
            }
            case CUSTOMS_CLEARED -> {
                Instant ready = fromTime.plus(Duration.ofDays(2 + r.nextInt(5))).plus(Duration.ofMinutes(r.nextInt(12 * 60)));
                yield nextDockSlot(ready.atZone(FC_ZONE.getOrDefault(c.fc(), ZoneId.of("America/Vancouver"))), r).toInstant();
            }
            case RECEIVED_FC -> throw new IllegalArgumentException("RECEIVED_FC is the last carrier stage");
        };
    }

    /** Putaway: the FC's dock-to-stock working days (no Sundays) after gate-in, during the day shift. */
    public static Instant goodsReceiptTime(String seed, Container c, Instant gateIn, int bufferDays) {
        ZoneId zone = FC_ZONE.getOrDefault(c.fc(), ZoneId.of("America/Vancouver"));
        LocalDate d = gateIn.atZone(zone).toLocalDate();
        for (int added = 0; added < bufferDays; ) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        SplittableRandom r = Rng.of(seed, "grn", c.poNumber());
        return d.atTime(LocalTime.of(8, 0)).plusMinutes(r.nextInt(8 * 60)).atZone(zone).toInstant();
    }

    /** Extra customs dwell for containers reaching this gateway in a congested ISO week (0 most weeks). */
    public static int congestionDays(String seed, String gateway, LocalDate arrivalDay) {
        int year = arrivalDay.get(IsoFields.WEEK_BASED_YEAR), week = arrivalDay.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        SplittableRandom r = Rng.of(seed, "congestion", gateway, year, week);
        return r.nextDouble() < CONGESTION_WEEKLY_PROBABILITY ? 7 + r.nextInt(8) : 0;
    }

    /** The dock accepts containers Monday to Saturday 07:00-15:00 local; anything else waits for the next slot. */
    static ZonedDateTime nextDockSlot(ZonedDateTime t, SplittableRandom r) {
        ZonedDateTime z = t;
        boolean open = z.getDayOfWeek() != DayOfWeek.SUNDAY && z.getHour() >= 7 && z.getHour() < 15;
        if (open) return z;
        LocalDate day = z.getHour() >= 15 ? z.toLocalDate().plusDays(1) : z.toLocalDate();
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.plusDays(1);
        return day.atTime(LocalTime.of(7, 0)).plusMinutes(r.nextInt(8 * 60)).atZone(z.getZone());
    }

    private static Instant plusDays(Instant t, int days, SplittableRandom r) {
        return t.plus(Duration.ofDays(days)).plus(Duration.ofMinutes(r.nextInt(12 * 60) - 6 * 60L));
    }
}
