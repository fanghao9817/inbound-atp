package com.haoyu.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * reserve, protect a B2B commitment, reject, replay, backorder against a new PO, receive the
 * container (allocation turns the backorder into a reservation), ship, cancel, and the ledger
 * still reconciles. Plus the concurrency guarantee: 20 parallel orders for 5 units sell exactly 5.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.seed.enabled=true")
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

    static final LocalDate TODAY = LocalDate.now(ZoneId.systemDefault());

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
    }

    record Response(int status, JsonNode body) {}

    private Response post(String path, Object body) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> new Response(res.getStatusCode().value(), json.readTree(res.getBody())), true);
    }

    private JsonNode get(String path) {
        return json.readTree(http.get().uri(path).retrieve().body(String.class));
    }

    private void createSku(String code, String fc, int onHand) {
        jdbc.sql("insert into sku (code, name, category) values (:c, :c, 'Test')").param("c", code).update();
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
        var body = new java.util.HashMap<String, Object>(Map.of("orderRef", ref, "channel", channel, "sku", sku, "qty", qty));
        if (fc != null) body.put("fc", fc);
        if (needBy != null) body.put("needBy", needBy.toString());
        return body;
    }

    @Test
    @Order(1)
    void reserveProtectRejectReplay() {
        createSku("TEST-ORD", "FC-RIC", 5);

        Response r1 = post("/api/orders", order("T-1", "ONLINE", "TEST-ORD", "FC-RIC", 3, null));
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r1.body().get("status").asString()).isEqualTo("RESERVED");
        assertThat(r1.body().get("promiseDate").asString()).isEqualTo(TODAY.toString());

        // a B2B order due in 20 days: today's 2 free units are enough, but they are not locked away now
        Response b2b = post("/api/orders", order("T-B2B", "B2B", "TEST-ORD", "FC-RIC", 2, TODAY.plusDays(20)));
        assertThat(b2b.body().get("status").asString()).isEqualTo("BACKORDERED");
        assertThat(b2b.body().get("promiseDate").asString()).isEqualTo(TODAY.plusDays(20).toString());

        // 2 units are still on the shelf, but both belong to the B2B commitment: an online order is rejected
        Response r2 = post("/api/orders", order("T-2", "ONLINE", "TEST-ORD", "FC-RIC", 1, null));
        assertThat(r2.body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 5).containsEntry("reserved", 3);

        // same orderRef again: 200 and the original order, nothing changes
        Response replay = post("/api/orders", order("T-1", "ONLINE", "TEST-ORD", "FC-RIC", 3, null));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("id").asLong()).isEqualTo(r1.body().get("id").asLong());
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("reserved", 3);
    }

    @Test
    @Order(2)
    void backorderAgainstInboundThenReceiveAllocatesIt() {
        Response po = post("/api/purchase-orders", Map.of(
                "clientRef", "TEST-PO-1", "supplier", "Test Supplier", "originPort", "VNSGN", "destFc", "FC-RIC",
                "lines", List.of(Map.of("sku", "TEST-ORD", "qty", 10))));
        assertThat(po.status()).isEqualTo(201);
        long shipmentId = po.body().get("shipmentId").asLong();
        assertThat(post("/api/purchase-orders", Map.of(
                "clientRef", "TEST-PO-1", "supplier", "Test Supplier", "originPort", "VNSGN", "destFc", "FC-RIC",
                "lines", List.of(Map.of("sku", "TEST-ORD", "qty", 10)))).status()).isEqualTo(200);   // idempotent

        Response online = post("/api/orders", order("T-3", "ONLINE", "TEST-ORD", "FC-RIC", 4, null));
        assertThat(online.body().get("status").asString()).isEqualTo("BACKORDERED");
        assertThat(LocalDate.parse(online.body().get("promiseDate").asString())).isAfter(TODAY.plusDays(20));

        Response received = post("/api/shipments/" + shipmentId + "/milestones", Map.of(
                "type", "RECEIVED_FC", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(), "source", "WMS",
                "receivedLines", List.of(Map.of("sku", "TEST-ORD", "qtyReceived", 10, "qtyDamaged", 1))));
        assertThat(received.status()).isEqualTo(202);

        // 9 sellable units in: the online backorder (no need-by) is reserved, the B2B one (due in 20 days) is not
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 14).containsEntry("reserved", 7);
        assertThat(get("/api/orders?status=RESERVED&limit=500").findValues("orderRef").stream().map(JsonNode::asString))
                .contains("T-3");
        assertThat(jdbc.sql("select status from purchase_order po join shipment s on s.po_id = po.id where s.id = :id")
                .param("id", shipmentId).query(String.class).single()).isEqualTo("RECEIVED");
    }

    @Test
    @Order(3)
    void shipCancelAndTheLedgerReconciles() {
        long t1 = get("/api/orders?status=RESERVED&limit=500").findParents("orderRef").stream()
                .filter(n -> n.get("orderRef").asString().equals("T-1")).findFirst().orElseThrow().get("id").asLong();
        assertThat(post("/api/orders/" + t1 + "/ship", Map.of()).body().get("status").asString()).isEqualTo("SHIPPED");
        assertThat(post("/api/orders/" + t1 + "/ship", Map.of()).status()).isEqualTo(200);         // idempotent
        assertThat(position("TEST-ORD", "FC-RIC")).containsEntry("on_hand", 11).containsEntry("reserved", 4);

        long b2b = jdbc.sql("select id from customer_order where order_ref = 'T-B2B'").query(Long.class).single();
        assertThat(post("/api/orders/" + b2b + "/cancel", Map.of()).body().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select count(*) from demand_commitment where order_id = :id").param("id", b2b).query(Long.class).single()).isZero();
        assertThat(post("/api/orders/" + t1 + "/cancel", Map.of()).status()).isEqualTo(409);       // shipped orders stay shipped

        // every position equals the sum of its ledger rows
        assertThat(jdbc.sql("""
                select count(*) from inventory_position ip
                left join (select sku_id, fc_id, sum(on_hand_delta) oh, sum(reserved_delta) rs from inventory_movement group by 1, 2) m
                  on m.sku_id = ip.sku_id and m.fc_id = ip.fc_id
                where ip.on_hand <> coalesce(m.oh, 0) or ip.reserved <> coalesce(m.rs, 0)
                """).query(Long.class).single()).isZero();
    }

    @Test
    @Order(4)
    void twentyConcurrentOrdersForFiveUnitsSellExactlyFive() throws Exception {
        createSku("TEST-RACE", "FC-CAL", 5);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Response>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String ref = "RACE-" + i;
            results.add(pool.submit((Callable<Response>) () -> post("/api/orders", order(ref, "ONLINE", "TEST-RACE", "FC-CAL", 1, null))));
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
    void milestonesFromTheFutureAreRejected() {
        long shipmentId = jdbc.sql("select s.id from shipment s join purchase_order po on po.id = s.po_id where po.status = 'OPEN' limit 1")
                .query(Long.class).single();
        Response r = post("/api/shipments/" + shipmentId + "/milestones", Map.of(
                "type", "ARRIVED_DEST_PORT", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).toString()));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @Order(6)
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
    @Order(7)
    void dashboardAndPlanningAnswer() {
        JsonNode kpis = get("/api/dashboard/kpis");
        assertThat(kpis.get("ordersToday").asInt()).isGreaterThanOrEqualTo(24);
        assertThat(kpis.get("openContainers").asInt()).isPositive();
        assertThat(get("/api/dashboard/daily?days=7")).hasSize(7);
        assertThat(get("/api/activity?limit=20")).hasSize(20);
        JsonNode plan = get("/api/planning/replenishment");
        assertThat(plan.size()).isGreaterThanOrEqualTo(48);
        assertThat(plan.get(0).has("weeksOfCover")).isTrue();
    }
}
