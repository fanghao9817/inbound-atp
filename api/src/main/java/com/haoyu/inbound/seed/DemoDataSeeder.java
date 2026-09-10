package com.haoyu.inbound.seed;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.eta.EtaRecalculationService;
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

    DemoDataSeeder(DataSource dataSource, AppProperties props, Clock clock, EtaRecalculationService recalculation) {
        this.dataSource = dataSource;
        this.props = props;
        this.clock = clock;
        this.recalculation = recalculation;
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
        var scored = recalculation.recalculateAllOpen();
        log.info("initial ETA scoring: {} open shipments, {} predictions set", scored.shipments(), scored.changed());
    }

    private void seed(Connection conn) throws SQLException {
        Random rnd = new Random(42);
        LocalDate today = LocalDate.now(clock);

        Map<String, Long> fcIds = insertFcs(conn);
        Map<String, Long> skuIds = insertSkus(conn);
        insertInventory(conn, rnd, skuIds, fcIds);
        insertDemand(conn, rnd, today, skuIds, fcIds);

        int poSeq = 1000;
        // a year of history: fully received containers with all five milestones
        for (int i = 0; i < 240; i++) {
            Lane lane = LANES.get(rnd.nextInt(LANES.size()));
            LocalDate departed = today.minusDays(20 + rnd.nextInt(345));
            insertPurchaseOrder(conn, rnd, lane, "PO-" + (poSeq++), departed, skuIds, fcIds, true, today);
        }
        // the live book: open containers spread across stages, a few of them late
        for (int i = 0; i < 36; i++) {
            Lane lane = LANES.get(rnd.nextInt(LANES.size()));
            LocalDate departed = today.minusDays(rnd.nextInt(40)).plusDays(rnd.nextInt(12));
            insertPurchaseOrder(conn, rnd, lane, "PO-" + (poSeq++), departed, skuIds, fcIds, false, today);
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
    }

    private void insertDemand(Connection conn, Random rnd, LocalDate today, Map<String, Long> skuIds, Map<String, Long> fcIds) throws SQLException {
        String[] refs = {"B2B", "TRADE", "PROMO", "STORE"};
        List<Long> skus = new ArrayList<>(skuIds.values());
        List<Long> fcs = new ArrayList<>(fcIds.values());
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into demand_commitment (sku_id, fc_id, qty, need_by, reference) values (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 48; i++) {
                ps.setLong(1, skus.get(rnd.nextInt(skus.size())));
                ps.setLong(2, fcs.get(rnd.nextInt(fcs.size())));
                ps.setInt(3, 5 + rnd.nextInt(36));
                ps.setObject(4, today.plusDays(3 + rnd.nextInt(45)));
                ps.setString(5, refs[rnd.nextInt(refs.length)] + "-" + (1000 + i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * One PO with 1–3 lines and one container. Milestone timings are drawn from the lane's typical
     * transit with log-normal noise, plus an occasional port delay, so the history has a real
     * distribution for dbt to summarise.
     */
    private void insertPurchaseOrder(Connection conn, Random rnd, Lane lane, String poNumber, LocalDate departed,
                                     Map<String, Long> skuIds, Map<String, Long> fcIds, boolean history, LocalDate today) throws SQLException {
        double noise = Math.exp(rnd.nextGaussian() * 0.15);
        int transit = (int) Math.round(lane.medianDays() * noise);
        int bookedLead = 3 + rnd.nextInt(5);
        int portDelay = rnd.nextInt(10) == 0 ? 5 + rnd.nextInt(8) : 0;       // 10% of containers get stuck at the port
        int toPort = (int) Math.round(transit * 0.78) + portDelay;
        int toCustoms = toPort + 1 + rnd.nextInt(4);
        int toReceived = toCustoms + 2 + rnd.nextInt(5);

        LocalDate booked = departed.minusDays(bookedLead);
        LocalDate plannedArrival = departed.plusDays((int) Math.round(lane.medianDays() * 0.78) + 1 + 2 + 2);
        String supplier = PORT_SUPPLIER.get(lane.origin());

        long poId;
        try (PreparedStatement ps = conn.prepareStatement("""
                insert into purchase_order (po_number, supplier, origin_port, dest_fc_id, status, planned_arrival, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, poNumber); ps.setString(2, supplier); ps.setString(3, lane.origin());
            ps.setLong(4, fcIds.get(lane.fc())); ps.setString(5, history ? "RECEIVED" : "OPEN");
            ps.setObject(6, plannedArrival); ps.setObject(7, booked.minusDays(20).atStartOfDay().atOffset(ZoneOffset.UTC));
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

        // which milestones have happened: all of them for history, up to "today" for open containers
        int[] offsets = {-bookedLead, 0, toPort, toCustoms, toReceived};
        MilestoneType[] types = MilestoneType.values();
        int reached = history ? 5 : 0;
        if (!history) {
            for (int i = 0; i < 5; i++) {
                if (!departed.plusDays(offsets[i]).isAfter(today)) reached = i + 1;
            }
            if (reached == 5) reached = 4;   // an open PO is by definition not received yet
        }
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

        try (PreparedStatement ps = conn.prepareStatement(
                "insert into shipment_milestone (shipment_id, type, occurred_at, source, event_id) values (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < reached; i++) {
                OffsetDateTime at = departed.plusDays(offsets[i]).atTime(6 + rnd.nextInt(12), rnd.nextInt(60)).atOffset(ZoneOffset.UTC);
                ps.setLong(1, shipmentId); ps.setString(2, types[i].name()); ps.setObject(3, at);
                ps.setString(4, i == 4 ? "WMS" : "CARRIER_EDI"); ps.setObject(5, UUID.randomUUID());
                ps.addBatch();
            }
            ps.executeBatch();
        }
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
