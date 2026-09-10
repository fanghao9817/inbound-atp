package com.haoyu.inbound.atp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Time-phased available-to-promise.
 *
 * <p>Projected stock on a date is {@code availableNow + supply arriving on or before that date
 * - demand due on or before that date}. The naive projection over-promises: stock that looks free
 * on day 10 may already be spoken for by a commitment due on day 20. ATP therefore takes the
 * forward-looking minimum of the projection ("look-ahead"): a quantity is promisable on a date
 * only if the projection never dips below it afterwards.
 *
 * <p>Pure function, no I/O, so it can be unit-tested exhaustively and reused by batch jobs.
 */
public final class AtpCalculator {

    public record Supply(LocalDate arrives, int qty, String source) {}

    public record Demand(LocalDate needBy, int qty, String reference) {}

    public record Point(LocalDate date, int projected, int atp) {}

    public record Result(int availableNow, List<Point> timeline) {

        /** Earliest date on which {@code qty} units can be promised, or empty if never within the horizon. */
        public java.util.Optional<LocalDate> earliestDateFor(int qty) {
            return timeline.stream().filter(p -> p.atp() >= qty).map(Point::date).findFirst();
        }
    }

    private AtpCalculator() {}

    public static Result compute(LocalDate today, int availableNow, List<Supply> supplies, List<Demand> demands, int horizonDays) {
        LocalDate horizon = today.plusDays(horizonDays);
        // net change per date; anything already in the past is treated as happening today
        TreeMap<LocalDate, Integer> delta = new TreeMap<>();
        delta.put(today, 0);
        for (Supply s : supplies) {
            LocalDate d = clamp(s.arrives(), today, horizon);
            if (d != null) delta.merge(d, s.qty(), Integer::sum);
        }
        for (Demand d : demands) {
            LocalDate dd = clamp(d.needBy(), today, horizon);
            if (dd != null) delta.merge(dd, -d.qty(), Integer::sum);
        }

        List<LocalDate> dates = new ArrayList<>(delta.keySet());
        int[] projected = new int[dates.size()];
        int running = availableNow;
        for (int i = 0; i < dates.size(); i++) {
            running += delta.get(dates.get(i));
            projected[i] = running;
        }
        // look-ahead: suffix minimum of the projection
        int[] atp = new int[dates.size()];
        int suffixMin = Integer.MAX_VALUE;
        for (int i = dates.size() - 1; i >= 0; i--) {
            suffixMin = Math.min(suffixMin, projected[i]);
            atp[i] = suffixMin;
        }
        List<Point> timeline = new ArrayList<>(dates.size());
        for (int i = 0; i < dates.size(); i++) {
            timeline.add(new Point(dates.get(i), projected[i], Math.max(atp[i], 0)));
        }
        return new Result(availableNow, List.copyOf(timeline));
    }

    /** Past dates collapse onto today; dates beyond the horizon are ignored (null). */
    private static LocalDate clamp(LocalDate d, LocalDate today, LocalDate horizon) {
        if (d.isAfter(horizon)) return null;
        return d.isBefore(today) ? today : d;
    }
}
