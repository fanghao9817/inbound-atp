package com.haoyu.inbound.seed;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.eta.EtaRecalculationService;
import com.haoyu.inbound.projection.AvailabilityProjectionService;
import com.haoyu.inbound.procurement.MilestoneType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Loads a deterministic demo dataset on first start: a year of received containers (the history dbt
 * learns lane lead times from) plus a live book of open purchase orders at various stages.
 * Plain JDBC with batched statements inside a single transaction: either the whole dataset lands or
 * none of it does.
 */
@Component
class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Fc(String code, String name, String region, int buffer) {}
    private record SkuDef(String code, String name, String category) {}
    /** Typical DEPARTED_ORIGIN → RECEIVED_FC transit in days for a lane. */
    private record Lane(String origin, String fc, double medianDays) {}

    private static final List<Fc> FCS = List.of(
            new Fc("FC-RIC", "Richmond, BC", "CA-WEST", 2),
            new Fc("FC-CAL", "Calgary, AB", "CA-WEST", 2),
            new Fc("FC-JAX", "Jacksonville, FL", "US-EAST", 3),
            new Fc("FC-PAT", "Patterson, CA", "US-WEST", 3));

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

    private static final Map<String, String> PORT_SUPPLIER = Map.of(
            "VNSGN", "Saigon Furniture Co.",
            "CNSHA", "Shanghai Home Works",
            "MYPKG", "Klang Valley Timber");

    private static final List<Lane> LANES = List.of(
            new Lane("VNSGN", "FC-RIC", 24), new Lane("VNSGN", "FC-CAL", 30), new Lane("VNSGN", "FC-JAX", 40), new Lane("VNSGN", "FC-PAT", 26),
            new Lane("CNSHA", "FC-RIC", 18), new Lane("CNSHA", "FC-CAL", 24), new Lane("CNSHA", "FC-JAX", 36), new Lane("CNSHA", "FC-PAT", 20),
            new Lane("MYPKG", "FC-RIC", 27), new Lane("MYPKG", "FC-CAL", 33), new Lane("MYPKG", "FC-JAX", 42), new Lane("MYPKG", "FC-PAT", 29));

    private static final String[] CARRIERS = {"Maersk", "ONE", "CMA CGM", "Evergreen"};

    private final DataSource dataSource;
    private final AppProperties props;
    private final Clock clock;
    private final EtaRecalculationService recalculation;
    private final AvailabilityProjectionService projection;

    DemoDataSeeder(DataSource dataSource, AppProperties props, Clock clock, EtaRecalculationService recalculation,
                   AvailabilityProjectionService projection) {
        this.dataSource = dataSource;
        this.props = props;
        this.clock = clock;
        this.recalculation = recalculation;
        this.projection = projection;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!props.seed().enabled()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (count(conn, "sku") > 0) {
                log.info("demo data already present, skipping seed");
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
        LocalDate today = LocalDate.now(clock);

        Map<String, Long> fcIds = insertFcs(conn);
        Map<String, Long> skuIds = insertSkus(conn);
        insertSourcing(conn);
        insertInventory(conn, rnd, skuIds, fcIds);
        insertDemand(conn, rnd, today, skuIds, fcIds);

        int poSeq = 1000;
        OffsetDateTime seededAt = OffsetDateTime.now(clock);
        // a year of history: fully received containers, every milestone before today (~40 per lane,
        // enough for HIGH confidence on late stages)
        for (int i = 0; i < 480; i++) {
            Lane lane = LANES.get(rnd.nextInt(LANES.size()));
            insertPurchaseOrder(conn, rnd, lane, "PO-" + (poSeq++), skuIds, fcIds, true, today, seededAt);
        }
        // the live book: open containers spread across stages, each with its next milestone still ahead
        for (int i = 0; i < 36; i++) {
            Lane lane = LANES.get(rnd.nextInt(LANES.size()));
            insertPurchaseOrder(conn, rnd, lane, "PO-" + (poSeq++), skuIds, fcIds, false, today, seededAt);
        }
    }

    private Map<String, Long> insertFcs(Connection conn) throws SQLException {
        Map<String, Long> ids = new java.util.HashMap<>();
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
        Map<String, Long> ids = new java.util.HashMap<>();
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

    private void insertInventory(Connection conn, Random rnd, Map<String, Long> skuIds, Map<String, Long> fcIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into inventory_position (sku_id, fc_id, on_hand, reserved) values (?, ?, ?, ?)")) {
            for (Long sku : skuIds.values()) {
                for (Long fc : fcIds.values()) {
                    int onHand = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(80);     // a quarter of positions are out of stock
                    int reserved = onHand == 0 ? 0 : rnd.nextInt(Math.min(onHand, 15) + 1);
                    ps.setLong(1, sku); ps.setLong(2, fc); ps.setInt(3, onHand); ps.setInt(4, reserved);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            // the same shape V2 migrates existing data into: an opening ledger row per position, and
            // every reserved unit belongs to an order waiting to be picked
            st.executeUpdate("""
                    insert into inventory_movement (sku_id, fc_id, kind, on_hand_delta, reserved_delta, ref_type)
                    select sku_id, fc_id, 'OPENING', on_hand, reserved, 'OPENING' from inventory_position
                    """);
            st.executeUpdate("""
                    insert into customer_order (order_ref, channel, origin, sku_id, fc_id, qty, status, promise_date, first_promise_date, reserved_at)
                    select 'SEED-RES-' || sku_id || '-' || fc_id, 'ONLINE', 'SEED', sku_id, fc_id, reserved, 'RESERVED', current_date, current_date, now()
                    from inventory_position where reserved > 0
                    """);
        }
    }

    /**
     * Open B2B and store orders due one to eight weeks out: SCHEDULED (time-phased demand, nothing locked
     * yet). The 06:00 commitment run reserves them when they come due, or re-promises them if supply slips.
     */
    private void insertDemand(Connection conn, Random rnd, LocalDate today, Map<String, Long> skuIds, Map<String, Long> fcIds) throws SQLException {
        String[] refs = {"B2B", "TRADE", "PROMO", "STORE"};
        List<Long> skus = new ArrayList<>(skuIds.values());
        List<Long> fcs = new ArrayList<>(fcIds.values());
        try (PreparedStatement order = conn.prepareStatement("""
                insert into customer_order (order_ref, channel, origin, sku_id, fc_id, qty, status, promise_date, first_promise_date, need_by)
                values (?, ?, 'SEED', ?, ?, ?, 'SCHEDULED', ?, ?, ?)
                """)) {
            for (int i = 0; i < 48; i++) {
                LocalDate needBy = today.plusDays(7 + rnd.nextInt(50));
                String prefix = refs[rnd.nextInt(refs.length)];
                order.setString(1, prefix + "-" + (1000 + i));
                order.setString(2, "STORE".equals(prefix) ? "STORE" : "B2B");
                order.setLong(3, skus.get(rnd.nextInt(skus.size())));
                order.setLong(4, fcs.get(rnd.nextInt(fcs.size())));
                order.setInt(5, 5 + rnd.nextInt(36));
                order.setObject(6, needBy); order.setObject(7, needBy); order.setObject(8, needBy);
                order.addBatch();
            }
            order.executeBatch();
        }
    }

    /**
     * One PO with 1–3 lines and one container. Milestone timings are drawn from the lane's typical
     * transit with log-normal noise, plus an occasional port delay, so the history has a real
     * distribution for dbt to summarise.
     */
    private void insertPurchaseOrder(Connection conn, Random rnd, Lane lane, String poNumber,
                                     Map<String, Long> skuIds, Map<String, Long> fcIds, boolean history, LocalDate today,
                                     OffsetDateTime seededAt) throws SQLException {
        double noise = Math.exp(rnd.nextGaussian() * 0.15);
        int transit = (int) Math.round(lane.medianDays() * noise);
        int bookedLead = 3 + rnd.nextInt(5);
        int portDelay = rnd.nextInt(10) == 0 ? 5 + rnd.nextInt(8) : 0;       // 10% of containers get stuck at the port
        int toPort = (int) Math.round(transit * 0.78) + portDelay;
        int toCustoms = toPort + 1 + rnd.nextInt(4);
        int toReceived = toCustoms + 2 + rnd.nextInt(5);

        // History is received at least a day ago; an open container departed recently enough that it
        // cannot have been received yet (some have not even departed). Transit is drawn first, so the
        // departure can be chosen to make that true.
        // (booking is never later than today: a PO dated in the future would be data from the future)
        LocalDate departed = history
                ? today.minusDays(toReceived + 1L + rnd.nextInt(330))
                : today.minusDays(rnd.nextInt(Math.max(1, toReceived))).plusDays(rnd.nextInt(bookedLead + 1));
        LocalDate booked = departed.minusDays(bookedLead);
        // carriers quote a conservative plan; only containers that are genuinely slow (port delays, bad weeks) end up late vs plan
        LocalDate plannedArrival = departed.plusDays((int) Math.round(lane.medianDays() * 0.78) + 11);
        String supplier = PORT_SUPPLIER.get(lane.origin());

        long poId;
        try (PreparedStatement ps = conn.prepareStatement("""
                insert into purchase_order (po_number, supplier, origin_port, dest_fc_id, status, planned_arrival, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, poNumber); ps.setString(2, supplier); ps.setString(3, lane.origin());
            ps.setLong(4, fcIds.get(lane.fc())); ps.setString(5, history ? "RECEIVED" : "OPEN");
            ps.setObject(6, plannedArrival); ps.setObject(7, bookedAt(booked, seededAt));   // PO placed = booked
            ps.executeUpdate();
            poId = generatedId(ps);
        }

        List<String> codes = new ArrayList<>(SKUS.stream().map(SkuDef::code).toList());
        java.util.Collections.shuffle(codes, rnd);
        int lines = 1 + rnd.nextInt(3);
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into purchase_order_line (po_id, sku_id, qty_ordered, qty_received) values (?, ?, ?, ?)")) {
            for (int i = 0; i < lines; i++) {
                int qty = 20 + rnd.nextInt(101);
                ps.setLong(1, poId); ps.setLong(2, skuIds.get(codes.get(i))); ps.setInt(3, qty); ps.setInt(4, history ? qty : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // which milestones have happened: compare full timestamps with the seeding instant
        int[] offsets = {-bookedLead, 0, toPort, toCustoms, toReceived};
        MilestoneType[] types = MilestoneType.values();
        OffsetDateTime[] at = new OffsetDateTime[5];
        for (int i = 0; i < 5; i++) {
            at[i] = departed.plusDays(offsets[i]).atTime(6 + rnd.nextInt(12), rnd.nextInt(60)).atZone(clock.getZone()).toOffsetDateTime();
        }
        at[0] = bookedAt(booked, seededAt);                                     // every PO is booked, so every container has a timeline
        int reached = 0;
        while (reached < 5 && !at[reached].isAfter(seededAt)) reached++;
        MilestoneType stage = reached == 0 ? MilestoneType.BOOKED : types[reached - 1];

        long shipmentId;
        try (PreparedStatement ps = conn.prepareStatement("""
                insert into shipment (po_id, carrier, container_no, planned_departure, planned_arrival, current_stage)
                values (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, poId); ps.setString(2, CARRIERS[rnd.nextInt(CARRIERS.length)]);
            ps.setString(3, String.format("%sU%07d", CARRIERS[0].substring(0, 3).toUpperCase(), rnd.nextInt(10_000_000)));
            ps.setObject(4, departed); ps.setObject(5, plannedArrival); ps.setString(6, stage.name());
            ps.executeUpdate();
            shipmentId = generatedId(ps);
        }

        // recorded = when the message reached us: EDI arrives 1-6 hours after the event, the WMS within the hour,
        // the booking at once - never after the seeding instant
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into shipment_milestone (shipment_id, type, occurred_at, source, event_id, recorded_at) values (?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < reached; i++) {
                String source = i == 0 ? "BUYER" : i == 4 ? "WMS" : i == 3 ? "CUSTOMS_BROKER" : "CARRIER_EDI";
                int lagMinutes = i == 0 ? 0 : i == 4 ? 5 + rnd.nextInt(55) : 60 + rnd.nextInt(300);
                OffsetDateTime recorded = at[i].plusMinutes(lagMinutes);
                ps.setLong(1, shipmentId); ps.setString(2, types[i].name()); ps.setObject(3, at[i]);
                ps.setString(4, source); ps.setObject(5, UUID.randomUUID());
                ps.setObject(6, recorded.isAfter(seededAt) ? seededAt : recorded);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** The booking moment: 09:00 on the booking day, or just before seeding if that is still to come today. */
    private OffsetDateTime bookedAt(LocalDate booked, OffsetDateTime seededAt) {
        OffsetDateTime at = booked.atTime(9, 0).atZone(clock.getZone()).toOffsetDateTime();
        return at.isAfter(seededAt) ? seededAt.minusMinutes(5) : at;
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
}
