package com.haoyu.inboundsim;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * What customers buy and when. Pure functions; every number is in one place so it can be explained.
 * <ul>
 *   <li>Network demand {@code baseWeeklyUnits} (675) - calibrated to what the inbound pipeline can supply.
 *   <li>FC share: Richmond 25%, Calgary 15%, Patterson 30%, Jacksonville 30%; each FC uses its own local time.
 *   <li>Day of week: Sun 1.20, Mon 1.15, Tue 1.00, Wed 0.95, Thu 0.95, Fri 0.85, Sat 0.90 (furniture is
 *       browsed on weekends and bought on Sunday/Monday).
 *   <li>Hour of day: night 0.15, morning 0.7, afternoon 1.0, evening (18-22) 1.8, late 0.6, normalised to mean 1.
 *   <li>Season: Black Friday week x1.1-1.3, Boxing week x1.2, January x0.85.
 *   <li>SKU popularity: Zipf with s = 0.8 over a fixed ranking; units per order depend on the product
 *       (sofas almost always 1, dining chairs come in sets, bar stools in 2-4).
 *   <li>Conversion: a customer shown a delivery date 0-14 days out buys; 15-35 days, 70% do; later, 30%.
 * </ul>
 */
public final class DemandModel {

    public record Fc(String code, double share, ZoneId zone) {}

    public static final List<Fc> FCS = List.of(
            new Fc("FC-RIC", 0.25, ZoneId.of("America/Vancouver")),
            new Fc("FC-CAL", 0.15, ZoneId.of("America/Edmonton")),
            new Fc("FC-PAT", 0.30, ZoneId.of("America/Los_Angeles")),
            new Fc("FC-JAX", 0.30, ZoneId.of("America/New_York")));

    /** Most to least popular. */
    public static final List<String> SKU_RANK = List.of(
            "SOFA-3S-OAT", "CHAIR-DIN-OAK", "TABLE-COF-WAL", "SOFA-2S-SLATE", "BED-Q-OAK", "STOOL-BAR-BLK",
            "SECT-L-CHAR", "CHAIR-LNG-WAL", "TABLE-DIN-OAK-6", "SHELF-5T-OAK", "DESK-STD-WAL", "BED-K-WAL");

    /** Units per order: P(1), P(2), P(3), P(4). */
    static final Map<String, double[]> QTY = Map.ofEntries(
            Map.entry("SOFA-3S-OAT", new double[] {0.95, 0.05, 0, 0}), Map.entry("SOFA-2S-SLATE", new double[] {0.95, 0.05, 0, 0}),
            Map.entry("SECT-L-CHAR", new double[] {1, 0, 0, 0}), Map.entry("CHAIR-DIN-OAK", new double[] {0.5, 0.35, 0.15, 0}),
            Map.entry("CHAIR-LNG-WAL", new double[] {0.8, 0.2, 0, 0}), Map.entry("STOOL-BAR-BLK", new double[] {0, 0.6, 0.2, 0.2}),
            Map.entry("TABLE-COF-WAL", new double[] {1, 0, 0, 0}), Map.entry("TABLE-DIN-OAK-6", new double[] {1, 0, 0, 0}),
            Map.entry("BED-Q-OAK", new double[] {1, 0, 0, 0}), Map.entry("BED-K-WAL", new double[] {1, 0, 0, 0}),
            Map.entry("SHELF-5T-OAK", new double[] {0.75, 0.25, 0, 0}), Map.entry("DESK-STD-WAL", new double[] {1, 0, 0, 0}));

    static final double[] DOW = {1.15, 1.00, 0.95, 0.95, 0.85, 0.90, 1.20};   // Monday .. Sunday
    static final double[] SKU_WEIGHT = zipf(SKU_RANK.size(), 0.8);
    static final double[] HOUR_WEIGHT = hourWeights();
    public static final double AVG_UNITS_PER_ORDER = averageUnits();

    private DemandModel() {}

    /** Expected online orders at one FC during the UTC hour starting at {@code hourStart}. */
    public static double ordersPerHour(double baseWeeklyUnits, Fc fc, Instant hourStart) {
        ZonedDateTime local = hourStart.atZone(fc.zone());
        double perHour = baseWeeklyUnits / AVG_UNITS_PER_ORDER / (7 * 24);
        return perHour * fc.share() * HOUR_WEIGHT[local.getHour()] * DOW[local.getDayOfWeek().getValue() - 1]
                * seasonal(local.toLocalDate());
    }

    public static double seasonal(LocalDate d) {
        if (d.getMonth() == Month.JANUARY) return 0.85;
        if (d.getMonth() == Month.NOVEMBER && d.getDayOfMonth() >= 27) return 1.3;          // Black Friday to Cyber Monday
        if (d.getMonth() == Month.NOVEMBER && d.getDayOfMonth() >= 20) return 1.1;
        if (d.getMonth() == Month.DECEMBER && d.getDayOfMonth() == 1) return 1.2;
        if (d.getMonth() == Month.DECEMBER && d.getDayOfMonth() >= 26) return 1.2;          // Boxing week
        return 1.0;
    }

    public static String pickSku(SplittableRandom r) {
        double u = r.nextDouble(), acc = 0;
        for (int i = 0; i < SKU_WEIGHT.length; i++) {
            acc += SKU_WEIGHT[i];
            if (u < acc) return SKU_RANK.get(i);
        }
        return SKU_RANK.getLast();
    }

    public static int pickQty(String sku, SplittableRandom r) {
        double[] p = QTY.getOrDefault(sku, new double[] {1, 0, 0, 0});
        double u = r.nextDouble(), acc = 0;
        for (int i = 0; i < p.length; i++) {
            acc += p[i];
            if (u < acc) return i + 1;
        }
        return 1;
    }

    public static Fc pickFc(SplittableRandom r) {
        double u = r.nextDouble(), acc = 0;
        for (Fc fc : FCS) {
            acc += fc.share();
            if (u < acc) return fc;
        }
        return FCS.getLast();
    }

    /** Share of customers who still buy when told the delivery date is {@code leadDays} away. */
    public static double conversion(long leadDays) {
        if (leadDays <= 14) return 1.0;
        if (leadDays <= 35) return 0.7;
        return 0.3;
    }

    public static boolean isWeekday(DayOfWeek d) {
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    static double[] zipf(int n, double s) {
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) sum += w[i] = 1 / Math.pow(i + 1, s);
        for (int i = 0; i < n; i++) w[i] /= sum;
        return w;
    }

    private static double[] hourWeights() {
        double[] w = new double[24];
        for (int h = 0; h < 24; h++) w[h] = h < 7 ? 0.15 : h < 12 ? 0.7 : h < 18 ? 1.0 : h < 23 ? 1.8 : 0.6;
        double mean = java.util.Arrays.stream(w).average().orElse(1);
        for (int h = 0; h < 24; h++) w[h] /= mean;
        return w;
    }

    private static double averageUnits() {
        double avg = 0;
        for (int i = 0; i < SKU_RANK.size(); i++) {
            double[] p = QTY.get(SKU_RANK.get(i));
            double mean = 0;
            for (int k = 0; k < p.length; k++) mean += (k + 1) * p[k];
            avg += SKU_WEIGHT[i] * mean;
        }
        return avg;
    }
}
