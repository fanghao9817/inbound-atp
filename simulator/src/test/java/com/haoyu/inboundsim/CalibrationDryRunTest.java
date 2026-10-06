package com.haoyu.inboundsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Half a year of the demand model against the replenishment policy the API uses, in memory, one day
 * at a time, for all 48 SKU x FC positions:
 * <pre>
 *   demand  Poisson(675 units/week x FC share x SKU weight / 7) per position per day
 *   supply  starts with the seeded book (about 30 on hand + about 107 in transit per position)
 *   policy  every Monday: f = observed weekly demand (blended with a prior of 14 for the first 4
 *           weeks), L = lane lead time, S = f x (L/7 + 1 review + 2 safety), order S - IP in cases of 5
 * </pre>
 * The claim being tested: after the 12-week warm-up the network serves most demand from stock
 * (fill rate 85-99%) without piling stock up (median weeks of cover under 20). If someone changes the
 * demand rate or the policy constants and breaks that balance, this test says so.
 */
class CalibrationDryRunTest {

    static final double BASE_WEEKLY_UNITS = 675;
    static final double PRIOR = 14;

    static final class Position {
        final int leadDays;
        final double expectedDaily;
        int onHand = 30, backlog = 0;
        final List<int[]> inbound = new ArrayList<>();      // {arrivalDay, qty}
        final int[] demand = new int[400];

        Position(int leadDays, double expectedDaily) {
            this.leadDays = leadDays;
            this.expectedDaily = expectedDaily;
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

    @Test
    void halfAYearKeepsFillRateHealthyWithoutPilingUpStock() {
        SplittableRandom r = new SplittableRandom(7);
        List<Position> positions = new ArrayList<>();
        for (int s = 0; s < DemandModel.SKU_RANK.size(); s++) {
            for (DemandModel.Fc fc : DemandModel.FCS) {
                double median = TransitModel.MEDIAN_DAYS.get(origin(DemandModel.SKU_RANK.get(s)) + "|" + fc.code());
                // booking -> departure 5, ocean + port + customs + drayage ~ median + 6, dock-to-stock 2-3, next sailing 7
                int lead = (int) Math.round(5 + median + 6 + 3 + 7);
                Position p = new Position(lead, BASE_WEEKLY_UNITS / 7 * fc.share() * DemandModel.SKU_WEIGHT[s]);
                for (int i = 0; i < 4; i++) p.inbound.add(new int[] {1 + r.nextInt(40), 27});   // the seeded pipeline
                positions.add(p);
            }
        }

        int days = 26 * 7;
        long demandAfterWarmup = 0, servedAfterWarmup = 0;
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
                    double f = w * observed + (1 - w) * PRIOR * (p.expectedDaily * 7 * 48 / BASE_WEEKLY_UNITS);
                    int s = (int) Math.ceil(f * (p.leadDays / 7.0 + 3));
                    int position = p.onHand + p.inboundUnits() - p.backlog;
                    int need = s - position;
                    if (need >= 10) p.inbound.add(new int[] {day + p.leadDays + r.nextInt(11) - 5, (int) Math.ceil(need / 5.0) * 5});
                }

                int d = poisson(r, p.expectedDaily);
                p.demand[day] = d;
                int served = Math.min(d, p.onHand);
                p.onHand -= served;
                p.backlog += d - served;
                if (day >= 12 * 7) {
                    demandAfterWarmup += d;
                    servedAfterWarmup += served;
                }
            }
        }

        double fillRate = servedAfterWarmup / (double) demandAfterWarmup;
        double[] cover = positions.stream()
                .mapToDouble(p -> (p.onHand + p.inboundUnits()) / Math.max(0.1, p.expectedDaily * 7))
                .sorted().toArray();
        double medianCover = cover[cover.length / 2];
        System.out.printf("dry run: fill rate %.3f after warm-up, median weeks of cover %.1f (p90 %.1f)%n",
                fillRate, medianCover, cover[(int) (cover.length * 0.9)]);
        assertThat(fillRate).isBetween(0.85, 0.99);
        assertThat(medianCover).isLessThan(20);
        assertThat(Arrays.stream(cover).max().orElse(0)).isLessThan(60);
    }

    static int poisson(SplittableRandom r, double lambda) {
        return Rng.poisson(r, lambda);
    }
}
