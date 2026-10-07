package com.haoyu.inbound.seed;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.eta.EtaRecalculationService;
import com.haoyu.inbound.orders.Channel;
import com.haoyu.inbound.orders.OrderService;
import com.haoyu.inbound.orders.Origin;
import com.haoyu.inbound.projection.AvailabilityProjectionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Loads a deterministic demo dataset on first start, shaped like a network that has been running for
 * a year under the same rules the simulator applies from go-live on:
 * <ul>
 *   <li>Demand: {@link #WEEKLY_UNITS} per SKU (last year's average) split over the FCs by
 *       {@link #FC_SHARE} - the same profile the simulator's customers follow.
 *   <li>Replenishment: every Monday each lane (origin port x FC) books one container that refills what
 *       sold since the last one (cases of 5, nothing below 10 units), so the pipeline in transit matches
 *       demand and lead time, as it does under the API's weekly (R,S) policy in steady state.
 *   <li>Transit: each container sails on (or a little after) its planned vessel date, crosses at the
 *       lane's typical time with noise and an occasional port delay, sometimes waits out a congested
 *       port, reaches the dock only Monday-Saturday 07:00-15:00 local, and is put away after the FC's
 *       dock-to-stock working days. Milestones are recorded with realistic EDI lag.
 *   <li>Stock: about two to three weeks of demand on hand per position, a few positions running short.
 *   <li>Forecast: last year's weekly demand per position with fitting noise, for the cold start.
 *   <li>B2B and store orders due in the coming weeks, placed through {@link OrderService} after the
 *       data is committed, so every promise is one ATP can keep.
 * </ul>
 * Everything is drawn from Random(42) relative to today. Plain JDBC with batched statements inside a
 * single transaction: either the whole dataset lands or none of it does.
 */
@Component
class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Fc(String code, String name, String region, int buffer, ZoneId zone, String gateway) {}
    private record SkuDef(String code, String name, String category) {}
    /** Typical DEPARTED_ORIGIN -> RECEIVED_FC transit in days for a lane. */
    private record Lane(String origin, String fc, double medianDays) {}

    private static final List<Fc> FCS = List.of(
            new Fc("FC-RIC", "Richmond, BC", "CA-WEST", 2, ZoneId.of("America/Vancouver"), "CAVAN"),
            new Fc("FC-CAL", "Calgary, AB", "CA-WEST", 2, ZoneId.of("America/Edmonton"), "CAVAN"),
            new Fc("FC-JAX", "Jacksonville, FL", "US-EAST", 3, ZoneId.of("America/New_York"), "USJAX"),
            new Fc("FC-PAT", "Patterson, CA", "US-WEST", 3, ZoneId.of("America/Los_Angeles"), "USOAK"));

    private static final List<SkuDef> SKUS = List.of(
            new SkuDef("SOFA-3S-OAT", "Three-seat sofa, oatmeal", "Sofas"),
            new SkuDef("SOFA-2S-SLATE", "Two-seat sofa, slate", "Sofas"),
            new SkuDef("SECT-L-CHAR", "L-sectional, charcoal", "Sofas"),
            new SkuDef("CHAIR-LNG-WAL", "Lounge chair, walnut", "Chairs"),
            new SkuDef("CHAIR-DIN-OAK", "Dining chair, oak (set of 2)", "Chairs"),
            new SkuDef("TABLE-DIN-OAK-6", "Dining table, oak, seats 6", "Tables"),
            new SkuDef("TABLE-COF-WAL", "Coffee table, walnut", "Tables"),
            new SkuDef("BED-Q-OAK", "Queen bed frame, oak", "Bedroom"),
            new SkuDef("BED-K-WAL", "King bed frame, walnut", "Bedroom"),
            new SkuDef("STOOL-BAR-BLK", "Bar stool, black", "Chairs"),
            new SkuDef("SHELF-5T-OAK", "Five-tier shelf, oak", "Storage"),
            new SkuDef("DESK-STD-WAL", "Standing desk, walnut", "Office"));

    /**
     * Network units per week: online demand (the simulator's DemandModel at 675 units/week: Zipf s=0.8
     * over orders times each SKU's units-per-order mix) plus B2B (about one 5-20 unit order per weekday,
     * SKU picked by the same Zipf weights). Keep in step with simulator/.../DemandModel.java.
     */
    static final Map<String, Double> WEEKLY_UNITS = Map.ofEntries(
            Map.entry("SOFA-3S-OAT", 149.3 + 16.2), Map.entry("CHAIR-DIN-OAK", 134.7 + 9.3),
            Map.entry("TABLE-COF-WAL", 59.0 + 6.7), Map.entry("SOFA-2S-SLATE", 49.2 + 5.4),
            Map.entry("BED-Q-OAK", 39.2 + 4.5), Map.entry("STOOL-BAR-BLK", 88.2 + 3.9),
            Map.entry("SECT-L-CHAR", 30.0 + 3.4), Map.entry("CHAIR-LNG-WAL", 32.3 + 3.1),
            Map.entry("TABLE-DIN-OAK-6", 24.5 + 2.8), Map.entry("SHELF-5T-OAK", 28.2 + 2.6),
            Map.entry("DESK-STD-WAL", 20.9 + 2.4), Map.entry("BED-K-WAL", 19.5 + 2.2));

    static final Map<String, Double> FC_SHARE = Map.of("FC-RIC", 0.25, "FC-CAL", 0.15, "FC-PAT", 0.30, "FC-JAX", 0.30);

    /** Same order weights as the simulator (Zipf s=0.8), for picking the SKU of a seeded B2B order. */
    private static final List<String> BY_POPULARITY = List.of(
            "SOFA-3S-OAT", "CHAIR-DIN-OAK", "TABLE-COF-WAL", "SOFA-2S-SLATE", "BED-Q-OAK", "STOOL-BAR-BLK",
            "SECT-L-CHAR", "CHAIR-LNG-WAL", "TABLE-DIN-OAK-6", "SHELF-5T-OAK", "DESK-STD-WAL", "BED-K-WAL");

    private static final Map<String, String> PORT_SUPPLIER = Map.of(
            "VNSGN", "Saigon Furniture Co.",
            "CNSHA", "Shanghai Home Works",
            "MYPKG", "Klang Valley Timber");

    private static final List<Lane> LANES = List.of(
            new Lane("VNSGN", "FC-RIC", 24), new Lane("VNSGN", "FC-CAL", 30), new Lane("VNSGN", "FC-JAX", 40), new Lane("VNSGN", "FC-PAT", 26),
            new Lane("CNSHA", "FC-RIC", 18), new Lane("CNSHA", "FC-CAL", 24), new Lane("CNSHA", "FC-JAX", 36), new Lane("CNSHA", "FC-PAT", 20),
            new Lane("MYPKG", "FC-RIC", 27), new Lane("MYPKG", "FC-CAL", 33), new Lane("MYPKG", "FC-JAX", 42), new Lane("MYPKG", "FC-PAT", 29));

    private static final String[] CARRIERS = {"Maersk", "ONE", "CMA CGM", "Evergreen"};
    private static final ZoneId HQ = ZoneId.of("America/Vancouver");
    private static final Map<String, ZoneId> GATEWAY_ZONE = Map.of("CAVAN", ZoneId.of("America/Vancouver"),
            "USOAK", ZoneId.of("America/Los_Angeles"), "USJAX", ZoneId.of("America/New_York"));
    private static final int HISTORY_WEEKS = 56;
    static final int CASE_PACK = 5;
    static final int MIN_ORDER = 10;
    static final int SEED_ORDERS = 20;

    private final DataSource dataSource;
    private final AppProperties props;
    private final Clock clock;
    private final EtaRecalculationService recalculation;
    private final AvailabilityProjectionService projection;
    private final OrderService orders;

    DemoDataSeeder(DataSource dataSource, AppProperties props, Clock clock, EtaRecalculationService recalculation,
                   AvailabilityProjectionService projection, OrderService orders) {
        this.dataSource = dataSource;
        this.props = props;
        this.clock = clock;
        this.recalculation = recalculation;
        this.projection = projection;
        this.orders = orders;
    }

    static String originOf(String category) {
        return switch (category) {
            case "Sofas" -> "VNSGN";
            case "Tables", "Bedroom" -> "MYPKG";
            default -> "CNSHA";
        };
    }

    static double weeklyDemand(String sku, String fc) {
        return WEEKLY_UNITS.get(sku) * FC_SHARE.get(fc);
    }

    @Override
    public void run(String... args) throws Exception {
        if (!props.seed().enabled()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (count(conn, "sku") > 0) {
                log.info("demo data already present, skipping seed");
                // a start that died between the commit and the seeded orders finishes them now (refs make it idempotent)
                if (count(conn, "customer_order where origin = 'SEED'") < SEED_ORDERS) placeSeedDemand();
                return;
            }
            conn.setAutoCommit(false);
            try {
                long t0 = System.nanoTime();
                seed(conn);
                conn.commit();
                log.info("demo data seeded in {} ms", (System.nanoTime() - t0) / 1_000_000);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
        var scored = recalculation.recalculateAllOpen(EtaRecalculationService.Reason.SEED);
        log.info("initial ETA scoring: {} open shipments, {} predictions set", scored.shipments(), scored.changed());
        placeSeedDemand();
        try {
            var projected = projection.projectAll();
            log.info("initial storefront projection: {} rows", projected.items());
        } catch (RuntimeException e) {
            // the data is committed; the projection catches up with the next availability.changed or project-all
            log.warn("initial storefront projection failed, continuing: {}", e.toString());
        }
    }

    private void seed(Connection conn) throws SQLException {
        Random rnd = new Random(42);
        Instant seededAt = clock.instant();

        Map<String, Long> fcIds = insertFcs(conn);
        Map<String, Long> skuIds = insertSkus(conn);
        insertSourcing(conn);
        insertForecast(conn, rnd, skuIds, fcIds);
        insertInventory(conn, rnd, skuIds, fcIds);

        Transit transit = new Transit(rnd, seededAt);
        int poSeq = 1000, open = 0, received = 0;
        LocalDate thisMonday = LocalDate.now(clock.withZone(HQ)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (Lane lane : LANES) {
            Fc fc = FCS.stream().filter(f -> f.code().equals(lane.fc())).findFirst().orElseThrow();
            List<String> skus = SKUS.stream().filter(s -> originOf(s.category()).equals(lane.origin())).map(SkuDef::code).toList();
            Map<String, Double> sold = new HashMap<>();
            for (String sku : skus) sold.put(sku, rnd.nextDouble() * weeklyDemand(sku, fc.code()));
            for (int w = HISTORY_WEEKS; w >= 0; w--) {
                Instant booked = thisMonday.minusWeeks(w).atTime(8, 0).plusMinutes(rnd.nextInt(240)).atZone(HQ).toInstant();
                if (booked.isAfter(seededAt)) continue;                                // this Monday's booking hasn't happened yet
                Map<String, Integer> lines = new LinkedHashMap<>();
                for (String sku : skus) {
                    double s = sold.get(sku) + weeklyDemand(sku, fc.code()) * (0.75 + rnd.nextDouble() * 0.5);
                    if (s >= MIN_ORDER) {
                        int qty = (int) Math.ceil(s / CASE_PACK) * CASE_PACK;
                        lines.put(sku, qty);
                        s -= qty;
                    }
                    sold.put(sku, s);
                }
                if (lines.isEmpty()) continue;
                boolean done = insertPurchaseOrder(conn, rnd, transit, lane, fc, "PO-" + (poSeq++), booked, lines, skuIds, fcIds, seededAt);
                if (done) received++; else open++;
            }
        }
        log.info("seeded {} received and {} open purchase orders", received, open);
    }

    private Map<String, Long> insertFcs(Connection conn) throws SQLException {
        Map<String, Long> ids = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into fulfillment_center (code, name, region, receiving_buffer_days) values (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (Fc fc : FCS) {
                ps.setString(1, fc.code()); ps.setString(2, fc.name()); ps.setString(3, fc.region()); ps.setInt(4, fc.buffer());
                ps.executeUpdate();
                ids.put(fc.code(), generatedId(ps));
            }
        }
        return ids;
    }

    private Map<String, Long> insertSkus(Connection conn) throws SQLException {
        Map<String, Long> ids = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into sku (code, name, category) values (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            for (SkuDef s : SKUS) {
                ps.setString(1, s.code()); ps.setString(2, s.name()); ps.setString(3, s.category());
                ps.executeUpdate();
                ids.put(s.code(), generatedId(ps));
            }
        }
        return ids;
    }

    /** Where each SKU is bought: sofas from Vietnam, tables and bedroom from Malaysia, the rest from China. */
    private void insertSourcing(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    insert into sku_source (sku_id, origin_port, supplier)
                    select id,
                           case when category = 'Sofas' then 'VNSGN' when category in ('Tables', 'Bedroom') then 'MYPKG' else 'CNSHA' end,
                           case when category = 'Sofas' then 'Saigon Furniture Co.' when category in ('Tables', 'Bedroom') then 'Klang Valley Timber'
                                else 'Shanghai Home Works' end
                    from sku
                    """);
        }
    }

    /** Last year's weekly demand per position, fitted with some error (a forecast is never exact). */
    private void insertForecast(Connection conn, Random rnd, Map<String, Long> skuIds, Map<String, Long> fcIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into demand_forecast (sku_id, fc_id, weekly_units, method) values (?, ?, ?, 'LAST_52_WEEKS_AVG')")) {
            for (SkuDef s : SKUS) {
                for (Fc fc : FCS) {
                    double units = weeklyDemand(s.code(), fc.code()) * (0.9 + rnd.nextDouble() * 0.2);
                    ps.setLong(1, skuIds.get(s.code())); ps.setLong(2, fcIds.get(fc.code()));
                    ps.setBigDecimal(3, java.math.BigDecimal.valueOf(Math.round(units * 100) / 100.0));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /**
     * About two to three weeks of demand on hand per position (the safety stock plus half a review
     * period the weekly policy holds on average); one position in twelve is running short. Nothing is
     * reserved yet: reservations come from live orders.
     */
    private void insertInventory(Connection conn, Random rnd, Map<String, Long> skuIds, Map<String, Long> fcIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into inventory_position (sku_id, fc_id, on_hand, reserved) values (?, ?, ?, 0)")) {
            for (SkuDef s : SKUS) {
                for (Fc fc : FCS) {
                    double weeks = rnd.nextInt(12) == 0 ? rnd.nextDouble() * 0.6 : 1.8 + rnd.nextDouble() * 1.6;
                    ps.setLong(1, skuIds.get(s.code())); ps.setLong(2, fcIds.get(fc.code()));
                    ps.setInt(3, (int) Math.round(weeklyDemand(s.code(), fc.code()) * weeks));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            // an opening ledger row per position, so the ledger sums to the position from day one
            st.executeUpdate("""
                    insert into inventory_movement (sku_id, fc_id, kind, on_hand_delta, reserved_delta, ref_type)
                    select sku_id, fc_id, 'OPENING', on_hand, reserved, 'OPENING' from inventory_position
                    """);
        }
    }

    /**
     * One booked container: PO, lines, shipment and the milestones already reported by the seeding
     * instant. Returns true when the container was put away before seeding (history).
     */
    private boolean insertPurchaseOrder(Connection conn, Random rnd, Transit transit, Lane lane, Fc fc, String poNumber, Instant booked,
                                        Map<String, Integer> lines, Map<String, Long> skuIds, Map<String, Long> fcIds,
                                        Instant seededAt) throws SQLException {
        Transit.Timeline t = transit.draw(lane, fc, booked);
        boolean history = t.goodsReceipt().isBefore(seededAt);

        long poId;
        try (PreparedStatement ps = conn.prepareStatement("""
                insert into purchase_order (po_number, supplier, origin_port, dest_fc_id, status, planned_arrival, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, poNumber); ps.setString(2, PORT_SUPPLIER.get(lane.origin())); ps.setString(3, lane.origin());
            ps.setLong(4, fcIds.get(lane.fc())); ps.setString(5, history ? "RECEIVED" : "OPEN");
            ps.setObject(6, t.plannedArrival()); ps.setObject(7, booked.atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
            poId = generatedId(ps);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into purchase_order_line (po_id, sku_id, qty_ordered, qty_received) values (?, ?, ?, ?)")) {
            for (var line : lines.entrySet()) {
                ps.setLong(1, poId); ps.setLong(2, skuIds.get(line.getKey())); ps.setInt(3, line.getValue()); ps.setInt(4, history ? line.getValue() : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // a milestone exists once it has been reported, i.e. its recorded time is before the seeding instant
        List<Transit.Event> reported = t.events().stream().filter(e -> !e.recorded().isAfter(seededAt)).toList();
        String stage = reported.isEmpty() ? "BOOKED" : reported.getLast().type();
        long shipmentId;
        try (PreparedStatement ps = conn.prepareStatement("""
                insert into shipment (po_id, carrier, container_no, planned_departure, planned_arrival, current_stage)
                values (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            String carrier = CARRIERS[rnd.nextInt(CARRIERS.length)];
            ps.setLong(1, poId); ps.setString(2, carrier);
            ps.setString(3, String.format("%sU%07d", carrier.replace(" ", "").substring(0, 3).toUpperCase(), rnd.nextInt(10_000_000)));
            ps.setObject(4, t.plannedDeparture()); ps.setObject(5, t.plannedArrival()); ps.setString(6, stage);
            ps.executeUpdate();
            shipmentId = generatedId(ps);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into shipment_milestone (shipment_id, type, occurred_at, source, event_id, recorded_at) values (?, ?, ?, ?, ?, ?)")) {
            for (Transit.Event e : reported) {
                ps.setLong(1, shipmentId); ps.setString(2, e.type()); ps.setObject(3, e.occurred().atOffset(java.time.ZoneOffset.UTC));
                ps.setString(4, e.source()); ps.setObject(5, UUID.randomUUID()); ps.setObject(6, e.recorded().atOffset(java.time.ZoneOffset.UTC));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return history;
    }

    /**
     * B2B and store orders due in two to eight weeks, placed through the normal order decision after the
     * data is committed (OrderService runs its own transactions), so each gets a promise ATP can keep:
     * SCHEDULED when stock or inbound covers it by the requested date, BACKORDERED otherwise.
     */
    private void placeSeedDemand() {
        Random rnd = new Random(43);
        LocalDate today = LocalDate.now(clock);
        String[] prefixes = {"B2B", "TRADE", "PROMO", "STORE"};
        double[] weights = new double[BY_POPULARITY.size()];
        for (int i = 0; i < weights.length; i++) weights[i] = 1 / Math.pow(i + 1, 0.8);
        double total = java.util.Arrays.stream(weights).sum();
        Map<String, Integer> outcome = new HashMap<>();
        for (int i = 0; i < SEED_ORDERS; i++) {
            String prefix = prefixes[rnd.nextInt(prefixes.length)];
            double u = rnd.nextDouble() * total, acc = 0;
            String sku = BY_POPULARITY.getLast();
            for (int k = 0; k < weights.length; k++) {
                acc += weights[k];
                if (u < acc) { sku = BY_POPULARITY.get(k); break; }
            }
            double v = rnd.nextDouble(), share = 0;
            String fc = FCS.getLast().code();
            for (Fc f : FCS) {
                share += FC_SHARE.get(f.code());
                if (v < share) { fc = f.code(); break; }
            }
            var placed = orders.place(new OrderService.PlaceOrder(prefix + "-" + (1000 + i),
                    "STORE".equals(prefix) ? Channel.STORE : Channel.B2B, Origin.SEED, sku, fc,
                    5 + rnd.nextInt(16), today.plusDays(14 + rnd.nextInt(43))));
            outcome.merge(placed.order().status(), 1, Integer::sum);
        }
        log.info("seeded B2B/store demand: {}", outcome);
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("no generated key");
            return keys.getLong(1);
        }
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * A container's true timeline, drawn with the rules the simulator's TransitModel applies live
     * (simulator/.../TransitModel.java), so the lane statistics dbt learns from history describe the
     * world the live containers move through:
     * <pre>
     *   planned departure  5-10 days after booking (the buyer books the next sailing)
     *   departed           on the planned day 75%, 1-3 days late 20%, rolled 4-7 days 5%
     *   arrived port       0.78 x lane median x lognormal(0, 0.15) days; 10% + 5-12 days stuck at port
     *   customs cleared    1-4 days; + 7-14 days when the gateway is congested that ISO week (8% of weeks)
     *   gate-in            2-6 days of drayage + 0-12 h, then the next dock slot Mon-Sat 07:00-15:00 FC-local
     *   goods receipt      the FC's dock-to-stock working days later (no Sundays), 08:00-16:00 local
     *   reported           EDI and customs 1-6 h after the event, the WMS within the hour, the booking at once
     * </pre>
     * Congestion here is drawn from the seeder's own Random(42), not the simulator's secret seed, and only for
 * port arrivals at least 19 days before seeding, so every container still open is one the simulator can
 * continue with its own congestion weeks. The carrier's planned arrival is padded to about P87.
     */
    static final class Transit {

        record Event(String type, Instant occurred, Instant recorded, String source) {}

        record Timeline(LocalDate plannedDeparture, LocalDate plannedArrival, Instant goodsReceipt, List<Event> events) {}

        private final Random rnd;
        private final Instant seededAt;
        private final Map<String, Integer> congestion = new HashMap<>();

        Transit(Random rnd, Instant seededAt) {
            this.rnd = rnd;
            this.seededAt = seededAt;
        }

        Timeline draw(Lane lane, Fc fc, Instant booked) {
            LocalDate bookedDay = booked.atZone(HQ).toLocalDate();
            LocalDate plannedDeparture = bookedDay.plusDays(5 + rnd.nextInt(6));
            // the carrier's quote: padded so that about 87% of containers make it (P87 of this transit model)
            LocalDate plannedArrival = plannedDeparture.plusDays(Math.round(lane.medianDays() * 0.78) + 16);

            double r = rnd.nextDouble();
            int late = r < 0.75 ? 0 : r < 0.95 ? 1 + rnd.nextInt(3) : 4 + rnd.nextInt(4);
            Instant departed = plannedDeparture.plusDays(late).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    .plus(Duration.ofMinutes(rnd.nextInt(24 * 60)));

            int ocean = (int) Math.max(1, Math.round(lane.medianDays() * Math.exp(rnd.nextGaussian() * 0.15) * 0.78));
            if (rnd.nextInt(10) == 0) ocean += 5 + rnd.nextInt(8);
            Instant port = jitter(departed.plus(Duration.ofDays(ocean)));

            // Congestion for containers that could still be at the port when the simulator takes over is the
            // simulator's to decide (from its own seed); the seeder congests only ports reached 19+ days earlier.
            LocalDate portDay = port.atZone(GATEWAY_ZONE.get(fc.gateway())).toLocalDate();
            int congested = port.isAfter(seededAt.minus(Duration.ofDays(19))) ? 0 : congestionDays(fc.gateway(), portDay);
            Instant customs = jitter(port.plus(Duration.ofDays(1 + rnd.nextInt(4) + congested)));

            Instant ready = customs.plus(Duration.ofDays(2 + rnd.nextInt(5))).plus(Duration.ofMinutes(rnd.nextInt(12 * 60)));
            Instant gateIn = nextDockSlot(ready.atZone(fc.zone())).toInstant();

            LocalDate d = gateIn.atZone(fc.zone()).toLocalDate();
            for (int added = 0; added < fc.buffer(); ) {
                d = d.plusDays(1);
                if (d.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
            }
            Instant grn = d.atTime(LocalTime.of(8, 0)).plusMinutes(rnd.nextInt(8 * 60)).atZone(fc.zone()).toInstant();

            List<Event> events = new ArrayList<>(List.of(
                    new Event("BOOKED", booked, booked, "BUYER"),
                    new Event("DEPARTED_ORIGIN", departed, departed.plus(Duration.ofMinutes(60 + rnd.nextInt(300))), "CARRIER_EDI"),
                    new Event("ARRIVED_DEST_PORT", port, port.plus(Duration.ofMinutes(60 + rnd.nextInt(300))), "CARRIER_EDI"),
                    new Event("CUSTOMS_CLEARED", customs, customs.plus(Duration.ofMinutes(60 + rnd.nextInt(300))), "CUSTOMS_BROKER"),
                    new Event("RECEIVED_FC", gateIn, gateIn.plus(Duration.ofMinutes(5 + rnd.nextInt(55))), "WMS")));
            return new Timeline(plannedDeparture, plannedArrival, grn, events);
        }

        private int congestionDays(String gateway, LocalDate day) {
            String key = gateway + "|" + day.get(IsoFields.WEEK_BASED_YEAR) + "|" + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            return congestion.computeIfAbsent(key, k -> rnd.nextDouble() < 0.08 ? 7 + rnd.nextInt(8) : 0);
        }

        private Instant jitter(Instant t) {
            return t.plus(Duration.ofMinutes(rnd.nextInt(12 * 60) - 6 * 60L));
        }

        /** The dock accepts containers Monday to Saturday 07:00-15:00 local; anything else waits for the next slot. */
        private ZonedDateTime nextDockSlot(ZonedDateTime t) {
            boolean open = t.getDayOfWeek() != DayOfWeek.SUNDAY && t.getHour() >= 7 && t.getHour() < 15;
            if (open) return t;
            LocalDate day = t.getHour() >= 15 ? t.toLocalDate().plusDays(1) : t.toLocalDate();
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.plusDays(1);
            return day.atTime(LocalTime.of(7, 0)).plusMinutes(rnd.nextInt(8 * 60)).atZone(t.getZone());
        }
    }
}
