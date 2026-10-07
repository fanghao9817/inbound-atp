package com.haoyu.inboundsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Half a year of the demand model against the replenishment policy the API uses, in memory, one day
 * at a time, for all 48 SKU x FC positions, starting from the state the API's seeder creates:
 * <pre>
 *   demand    Poisson(675 units/week x FC share x SKU unit share / 7) per position per day
 *   opening   1.8-3.4 weeks of demand on hand (one position in twelve short at 0-0.6 weeks), and the
 *             pipeline of a lane that has booked one container a week refilling what sold
 *   forecast  each position's true weekly demand x 0.9-1.1 (fitted on last year's sales)
 *   policy    every Monday: f = observed weekly demand blended with the forecast over the first 4
 *             weeks, L = lane lead time, S = f x (L/7 + 1 review + 2 safety), order S - IP in cases of 5
 * </pre>
 * The claims being tested: the network serves most demand from stock from the first week on (cold
 * start included) and keeps doing so (fill rate 85-99%), without piling stock up (median weeks of
 * cover under 20). If someone changes the demand rate, the seeding or the policy constants and breaks
 * that balance, this test says so.
 */
class CalibrationDryRunTest {

    static final double BASE_WEEKLY_UNITS = 675;

    static final class Position {
        final int leadDays;
        final double weekly;
        final double forecast;
        int onHand, backlog = 0;
        final List<int[]> inbound = new ArrayList<>();      // {arrivalDay, qty}
        final int[] demand = new int[400];

        Position(int leadDays, double weekly, double forecast) {
            this.leadDays = leadDays;
            this.weekly = weekly;
            this.forecast = forecast;
        }

        int inboundUnits() {
            return inbound.stream().mapToInt(a -> a[1]).sum();
        }
    }

    static String origin(String sku) {
        if (sku.startsWith("SOFA") || sku.startsWith("SECT")) return "VNSGN";
        if (sku.startsWith("TABLE") || sku.startsWith("BED")) return "MYPKG";
        return "CNSHA";
    }

    /** Network units per week by SKU: order share (Zipf) times that SKU's mean units per order. */
    static double[] unitShares() {
        double[] u = new double[DemandModel.SKU_RANK.size()];
        double total = 0;
        for (int i = 0; i < u.length; i++) {
            double[] p = DemandModel.QTY.get(DemandModel.SKU_RANK.get(i));
            double mean = 0;
            for (int k = 0; k < p.length; k++) mean += (k + 1) * p[k];
            total += u[i] = DemandModel.SKU_WEIGHT[i] * mean;
        }
        for (int i = 0; i < u.length; i++) u[i] /= total;
        return u;
    }

    @Test
    void halfAYearFromTheSeededStateKeepsFillRateHealthyWithoutPilingUpStock() {
        double[] share = unitShares();
        double coldFill = 0, warmFill = 0, worstColdWeek = 1;
        double[] medianCover = new double[3];
        for (int run = 0; run < 3; run++) {
            Result r = simulate(new SplittableRandom(7 + run), share);
            coldFill += r.coldFill / 3;
            warmFill += r.warmFill / 3;
            worstColdWeek = Math.min(worstColdWeek, r.worstColdWeek);
            medianCover[run] = r.medianCover;
        }
        System.out.printf("dry run: fill weeks 0-12 %.3f (worst week %.3f), after warm-up %.3f, median weeks of cover %s%n",
                coldFill, worstColdWeek, warmFill, Arrays.toString(medianCover));
        assertThat(coldFill).isBetween(0.85, 0.995);
        assertThat(worstColdWeek).isGreaterThan(0.7);
        assertThat(warmFill).isBetween(0.85, 0.995);
        assertThat(Arrays.stream(medianCover).max().orElse(0)).isLessThan(20);
    }

    record Result(double coldFill, double warmFill, double worstColdWeek, double medianCover) {}

    static Result simulate(SplittableRandom r, double[] share) {
        List<Position> positions = new ArrayList<>();
        for (int s = 0; s < DemandModel.SKU_RANK.size(); s++) {
            String sku = DemandModel.SKU_RANK.get(s);
            for (DemandModel.Fc fc : DemandModel.FCS) {
                double median = TransitModel.MEDIAN_DAYS.get(origin(sku) + "|" + fc.code());
                // booking -> planned sailing 7, ocean ~0.78 x median, port + customs + drayage ~ 8, dock-to-stock 3
                int lead = (int) Math.round(7 + median * 0.78 + 8 + 3);
                double weekly = BASE_WEEKLY_UNITS * fc.share() * share[s];
                Position p = new Position(lead, weekly, weekly * (0.9 + r.nextDouble() * 0.2));
                double weeksOnHand = r.nextInt(12) == 0 ? r.nextDouble() * 0.6 : 1.8 + r.nextDouble() * 1.6;
                p.onHand = (int) Math.round(weekly * weeksOnHand);
                // the seeded pipeline: one booking a week, refilling what sold; still in transit if not yet arrived
                double sold = r.nextDouble() * weekly;
                for (int w = lead / 7 + 1; w >= 1; w--) {
                    sold += weekly * (0.75 + r.nextDouble() * 0.5);
                    if (sold < 10) continue;
                    int qty = (int) Math.ceil(sold / 5) * 5;
                    sold -= qty;
                    int arrival = -7 * w + lead + r.nextInt(7) - 3;
                    if (arrival > 0) p.inbound.add(new int[] {arrival, qty});   // arrived ones are in the opening stock
                }
                positions.add(p);
            }
        }

        int days = 26 * 7;
        long coldDemand = 0, coldServed = 0, warmDemand = 0, warmServed = 0;
        long[] weekDemand = new long[26], weekServed = new long[26];
        for (int day = 0; day < days; day++) {
            for (Position p : positions) {
                for (var it = p.inbound.iterator(); it.hasNext(); ) {
                    int[] a = it.next();
                    if (a[0] <= day) { p.onHand += a[1]; it.remove(); }
                }
                int fromBacklog = Math.min(p.backlog, p.onHand);           // backorders are served first
                p.backlog -= fromBacklog;
                p.onHand -= fromBacklog;

                if (day % 7 == 0) {
                    double observedDays = Math.min(day, 28);
                    int units = 0;
                    for (int d = Math.max(0, day - 28); d < day; d++) units += p.demand[d];
                    double observed = observedDays == 0 ? 0 : units * 7.0 / observedDays;
                    double w = observedDays / 28;
                    double f = w * observed + (1 - w) * p.forecast;
                    int s = (int) Math.ceil(f * (p.leadDays / 7.0 + 3));
                    int position = p.onHand + p.inboundUnits() - p.backlog;
                    int need = s - position;
                    if (need >= 10) p.inbound.add(new int[] {day + p.leadDays + r.nextInt(11) - 5, (int) Math.ceil(need / 5.0) * 5});
                }

                int d = Rng.poisson(r, p.weekly / 7);
                p.demand[day] = d;
                int served = Math.min(d, p.onHand);
                p.onHand -= served;
                p.backlog += d - served;
                weekDemand[day / 7] += d;
                weekServed[day / 7] += served;
                if (day < 12 * 7) { coldDemand += d; coldServed += served; }
                else { warmDemand += d; warmServed += served; }
            }
        }
        double worst = 1;
        for (int w = 0; w < 12; w++) worst = Math.min(worst, weekServed[w] / (double) Math.max(1, weekDemand[w]));
        double[] cover = positions.stream()
                .mapToDouble(p -> (p.onHand + p.inboundUnits()) / Math.max(0.1, p.weekly))
                .sorted().toArray();
        return new Result(coldServed / (double) coldDemand, warmServed / (double) warmDemand, worst, cover[cover.length / 2]);
    }
}
