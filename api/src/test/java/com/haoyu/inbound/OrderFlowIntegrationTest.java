package com.haoyu.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demand side end to end, on SKUs created just for this test so the numbers are exact:
 * reserve, schedule a B2B order without locking stock, reject, replay, backorder against a new PO,
 * gate-in then goods receipt (allocation turns the backorder into a reservation), ship, cancel, and
 * the ledger still reconciles. Plus: 20 parallel orders for 5 units sell exactly 5; the internal API
 * answers 404 without the token; visitors get a capped, labelled order; previews write nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.seed.enabled=true", "app.internal-token=test-token"})
@Import({TestcontainersConfig.class, TestProjectionConfig.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderFlowIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaAdmin kafkaAdmin;

    RestClient http;

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Vancouver"));

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
    }

    record Response(int status, JsonNode body) {}

    private Response call(String path, Object body, boolean internal) {
        var req = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
        if (internal) req = req.header("X-Internal-Token", "test-token");
        return req.exchange((rq, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Response(res.getStatusCode().value(), bytes.length == 0 ? null : json.readTree(bytes));
        }, true);
    }

    private Response internal(String path, Object body) {
        return call(path, body, true);
    }

    private JsonNode get(String path) {
        return json.readTree(http.get().uri(path).retrieve().body(String.class));
    }

    private void createSku(String code, String fc, int onHand) {
        jdbc.sql("insert into sku (code, name, category) values (:c, :c, 'Sofas')").param("c", code).update();
        jdbc.sql("insert into sku_source (sku_id, origin_port, supplier) select id, 'VNSGN', 'Test Supplier' from sku where code = :c")
                .param("c", code).update();
        jdbc.sql("""
                insert into inventory_position (sku_id, fc_id, on_hand, reserved)
                select sku.id, fc.id, :q, 0 from sku, fulfillment_center fc where sku.code = :c and fc.code = :fc
                """).param("c", code).param("fc", fc).param("q", onHand).update();
        jdbc.sql("""
                insert into inventory_movement (sku_id, fc_id, kind, on_hand_delta, reserved_delta, ref_type)
                select sku.id, fc.id, 'OPENING', :q, 0, 'OPENING' from sku, fulfillment_center fc where sku.code = :c and fc.code = :fc
                """).param("c", code).param("fc", fc).param("q", onHand).update();
    }

    private Map<String, Object> position(String sku, String fc) {
        return jdbc.sql("""
                select ip.on_hand, ip.reserved from inventory_position ip
                join sku on sku.id = ip.sku_id join fulfillment_center fc on fc.id = ip.fc_id
                where sku.code = :sku and fc.code = :fc
                """).param("sku", sku).param("fc", fc).query().singleRow();
    }

    private Map<String, Object> order(String ref, String channel, String sku, String fc, int qty, LocalDate needBy) {
        var body = new HashMap<String, Object>(Map.of("orderRef", ref, "channel", channel, "sku", sku, "qty", qty));
        if (fc != null) body.put("fc", fc);
        if (needBy != null) body.put("needBy", needBy.toString());
        return body;
    }

    private Map<String, Object> milestone(String type) {
        return Map.of("type", type, "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(), "source", "WMS",
                "eventId", UUID.randomUUID().toString());
    }

    @Test
    @Order(1)
    void reserveScheduleRejectReplay() {
        createSku("TEST-ORD", "FC-RIC", 5);

        Response r1 = internal("/api/internal/orders", order("T-1", "ONLINE", "TEST-ORD", "FC-RIC", 3, null));
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r1.body().get("status").asString()).isEqualTo("RESERVED");
        assertThat(r1.body().get("promiseDate").asString()).isEqualTo(TODAY.toString());
        assertThat(r1.body().get("origin").asString()).isEqualTo("FEED");

        // a B2B order due in 20 days: covered, so SCHEDULED - it holds its date but locks no stock yet
        Response b2b = internal("/api/internal/orders", order("T-B2B", "B2B", "TEST-ORD", "FC-RIC", 2, TODAY.plusDays(20)));
        assertThat(b2b.body().get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(b2b.body().get("promiseDate").asString()).isEqualTo(TODAY.plusDays(20).toString());
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("reserved", 3);

        // 2 units are still on the shelf, but both belong to the scheduled order: an online order is rejected
        Response r2 = internal("/api/internal/orders", order("T-2", "ONLINE", "TEST-ORD", "FC-RIC", 1, null));
        assertThat(r2.body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 5).containsEntry("reserved", 3);

        // same orderRef again: 200 and the original order, nothing changes
        Response replay = internal("/api/internal/orders", order("T-1", "ONLINE", "TEST-ORD", "FC-RIC", 3, null));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("id").asLong()).isEqualTo(r1.body().get("id").asLong());
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("reserved", 3);
    }

    @Test
    @Order(2)
    void backorderAgainstInboundThenGateInAndGoodsReceiptAllocatesIt() {
        Map<String, Object> po = Map.of("clientRef", "TEST-PO-1", "supplier", "Test Supplier", "originPort", "VNSGN", "destFc", "FC-RIC",
                "lines", List.of(Map.of("sku", "TEST-ORD", "qty", 10)));
        Response created = internal("/api/internal/purchase-orders", po);
        assertThat(created.status()).isEqualTo(201);
        long shipmentId = created.body().get("shipmentId").asLong();
        assertThat(internal("/api/internal/purchase-orders", po).status()).isEqualTo(200);                 // idempotent

        Response online = internal("/api/internal/orders", order("T-3", "ONLINE", "TEST-ORD", "FC-RIC", 4, null));
        assertThat(online.body().get("status").asString()).isEqualTo("BACKORDERED");
        assertThat(LocalDate.parse(online.body().get("promiseDate").asString())).isAfter(TODAY.plusDays(20));

        // goods receipt before the container is at the dock is refused
        Map<String, Object> grn = Map.of("eventId", UUID.randomUUID().toString(), "receivedAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "lines", List.of(Map.of("sku", "TEST-ORD", "qtyReceived", 10, "qtyDamaged", 1)));
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/receipt", grn).status()).isEqualTo(409);

        // gate-in moves the container, not the stock
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", milestone("RECEIVED_FC")).status()).isEqualTo(202);
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 5);

        // putaway: 9 sellable units; the online backorder (no need-by) is reserved, the scheduled B2B one is not due yet
        Response posted = internal("/api/internal/shipments/" + shipmentId + "/receipt", grn);
        assertThat(posted.status()).isEqualTo(201);
        assertThat(posted.body().get("unitsDamaged").asInt()).isEqualTo(1);
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 14).containsEntry("reserved", 7);
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/receipt", grn).status()).isEqualTo(200);   // replay
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 14);
        assertThat(jdbc.sql("select status from customer_order where order_ref = 'T-3'").query(String.class).single()).isEqualTo("RESERVED");
        assertThat(jdbc.sql("select status from purchase_order po join shipment s on s.po_id = po.id where s.id = :id")
                .param("id", shipmentId).query(String.class).single()).isEqualTo("RECEIVED");
    }

    @Test
    @Order(3)
    void shipCancelAndTheLedgerReconciles() {
        long t1 = jdbc.sql("select id from customer_order where order_ref = 'T-1'").query(Long.class).single();
        assertThat(internal("/api/internal/orders/" + t1 + "/ship", Map.of()).body().get("status").asString()).isEqualTo("SHIPPED");
        assertThat(internal("/api/internal/orders/" + t1 + "/ship", Map.of()).status()).isEqualTo(200);          // idempotent
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 11).containsEntry("reserved", 4);

        long b2b = jdbc.sql("select id from customer_order where order_ref = 'T-B2B'").query(Long.class).single();
        assertThat(internal("/api/internal/orders/" + b2b + "/cancel", Map.of()).body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select count(*) from demand_commitment where order_id = :id").param("id", b2b).query(Long.class).single()).isZero();
        assertThat(internal("/api/internal/orders/" + t1 + "/cancel", Map.of()).status()).isEqualTo(409);       // shipped stays shipped

        assertThat(jdbc.sql("""
                select count(*) from inventory_position ip
                left join (select sku_id, fc_id, sum(on_hand_delta) oh, sum(reserved_delta) rs from inventory_movement group by 1, 2) m
                  on m.sku_id = ip.sku_id and m.fc_id = ip.fc_id
                where ip.on_hand <> coalesce(m.oh, 0) or ip.reserved <> coalesce(m.rs, 0)
                """).query(Long.class).single()).as("positions that differ from their ledger").isZero();
        assertThat(jdbc.sql("""
                select count(*) from inventory_position ip
                where ip.reserved <> coalesce((select sum(qty) from customer_order o
                                               where o.status = 'RESERVED' and o.sku_id = ip.sku_id and o.fc_id = ip.fc_id), 0)
                """).query(Long.class).single()).as("positions whose reserved units are not exactly the RESERVED orders").isZero();
    }

    @Test
    @Order(4)
    void twentyConcurrentOrdersForFiveUnitsSellExactlyFive() throws Exception {
        createSku("TEST-RACE", "FC-CAL", 5);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Response>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String ref = "RACE-" + i;
            results.add(pool.submit((Callable<Response>) () -> internal("/api/internal/orders", order(ref, "ONLINE", "TEST-RACE", "FC-CAL", 1, null))));
        }
        int reserved = 0, rejected = 0;
        for (Future<Response> f : results) {
            String status = f.get().body().get("status").asString();
            if (status.equals("RESERVED")) reserved++;
            if (status.equals("REJECTED")) rejected++;
        }
        pool.shutdown();
        assertThat(reserved).isEqualTo(5);
        assertThat(rejected).isEqualTo(15);
        assertThat(position("TEST-RACE", "FC-CAL")).containsEntry("on_hand", 5).containsEntry("reserved", 5);
    }

    @Test
    @Order(5)
    void milestoneValidation() {
        long shipmentId = jdbc.sql("""
                select s.id from shipment s join purchase_order po on po.id = s.po_id
                where po.status = 'OPEN' and s.current_stage = 'DEPARTED_ORIGIN' limit 1
                """).query(Long.class).single();
        Map<String, Object> future = Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).toString(),
                "source", "CARRIER_EDI", "eventId", UUID.randomUUID().toString());
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", future).status()).isEqualTo(400);
        Map<String, Object> beforeDeparture = Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).minusYears(2).toString(),
                "source", "CARRIER_EDI", "eventId", UUID.randomUUID().toString());
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", beforeDeparture).status()).isEqualTo(400);
        Map<String, Object> freeText = Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "source", "<script>", "eventId", UUID.randomUUID().toString());
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", freeText).status()).isEqualTo(400);

        // the same stage reported again at a different time is a conflict, not a silent overwrite
        Map<String, Object> port = Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).toString(),
                "source", "CARRIER_EDI", "eventId", UUID.randomUUID().toString());
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", port).status()).isEqualTo(202);
        Map<String, Object> conflicting = Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString(),
                "source", "CARRIER_EDI", "eventId", UUID.randomUUID().toString());
        assertThat(internal("/api/internal/shipments/" + shipmentId + "/milestones", conflicting).status()).isEqualTo(409);
    }

    @Test
    @Order(6)
    void internalApiIsInvisibleWithoutTheTokenAndVisitorsGetACappedLabelledOrder() {
        assertThat(call("/api/internal/orders", order("T-NOTOKEN", "ONLINE", "TEST-ORD", "FC-RIC", 1, null), false).status()).isEqualTo(404);

        Response visitor = call("/api/orders", Map.of("sku", "SOFA-3S-OAT", "fc", "FC-RIC", "qty", 1), false);
        assertThat(visitor.status()).isEqualTo(201);
        assertThat(visitor.body().get("orderRef").asString()).startsWith("VISIT-");
        assertThat(visitor.body().get("origin").asString()).isEqualTo("VISITOR");
        assertThat(call("/api/orders", Map.of("sku", "SOFA-3S-OAT", "fc", "FC-RIC", "qty", 50), false).status()).isEqualTo(400);

        long shipmentId = jdbc.sql("select s.id from shipment s join purchase_order po on po.id = s.po_id where po.status = 'OPEN' and s.current_stage = 'DEPARTED_ORIGIN' limit 1")
                .query(Long.class).single();
        long milestonesBefore = jdbc.sql("select count(*) from shipment_milestone").query(Long.class).single();
        Response preview = call("/api/shipments/" + shipmentId + "/eta-preview",
                Map.of("type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString()), false);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().hasNonNull("previewArrival")).isTrue();
        assertThat(jdbc.sql("select count(*) from shipment_milestone").query(Long.class).single()).isEqualTo(milestonesBefore);
    }

    @Test
    @Order(7)
    void everyEventLeavesTheOutboxAndPoisonGoesToTheDlt() throws Exception {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.sql("select count(*) from outbox_event where published_at is null").query(Long.class).single()).isZero());

        kafka.send("availability.changed", "X|Y", "this is not json").get();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                long end = admin.listOffsets(Map.of(new TopicPartition("availability.changed.dlt", 0), OffsetSpec.latest()))
                        .all().get().values().iterator().next().offset();
                assertThat(end).isPositive();
            });
        }
    }

    @Test
    @Order(8)
    void dashboardPlanningAndExceptionsAnswer() {
        JsonNode kpis = get("/api/dashboard/kpis");
        assertThat(kpis.get("ordersToday").asInt()).isGreaterThanOrEqualTo(24);     // FEED orders placed by this test
        assertThat(kpis.get("openContainers").asInt()).isPositive();
        assertThat(kpis.get("outboxPending").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(get("/api/dashboard/daily?days=7")).hasSize(7);
        assertThat(get("/api/activity?limit=20")).hasSize(20);
        JsonNode plan = get("/api/planning/replenishment");
        assertThat(plan.get("lines").size()).isGreaterThanOrEqualTo(48);
        assertThat(plan.get("lines").get(0).has("orderUpTo")).isTrue();
        plan.get("proposals").forEach(p -> assertThat(p.get("clientRef").asString()).startsWith("REPL-"));
        assertThat(get("/api/exceptions/shortages").isArray()).isTrue();
        assertThat(get("/api/exceptions").isArray()).isTrue();
    }
}
