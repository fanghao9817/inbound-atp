package com.haoyu.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import com.haoyu.inbound.projection.AvailabilityItem;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end through HTTP, PostgreSQL and Kafka: seeded data → ATP/fulfillment quotes agree with each
 * other → a milestone flows through Kafka into a new prediction → replays are ignored → refreshed lane
 * statistics change the prediction and its confidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.seed.enabled=true")
@Import({TestcontainersConfig.class, TestProjectionConfig.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboundAtpIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestProjectionConfig.RecordingSink sink;

    RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
    }

    private JsonNode get(String path) {
        return json.readTree(http.get().uri(path).retrieve().body(String.class));
    }

    private JsonNode post(String path, Object body) {
        return json.readTree(http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }

    @Test
    @Order(1)
    void seedIsLoadedAndCatalogIsServed() {
        assertThat(get("/api/skus")).hasSize(12);
        assertThat(get("/api/fulfillment-centers")).hasSize(4);
        assertThat(get("/api/purchase-orders?status=OPEN").size()).isBetween(30, 36);
        assertThat(get("/actuator/health").get("status").asString()).isEqualTo("UP");
        // the seeder projects every SKU x FC once the data is in
        assertThat(sink.batches).anySatisfy(b -> {
            assertThat(b.getKey()).isEqualTo("full-refresh");
            assertThat(b.getValue()).hasSize(12 * 4);
        });
        JsonNode config = get("/api/config");
        assertThat(config.get("projectionEnabled").asBoolean()).isFalse();
        assertThat(post("/api/availability/project-all", Map.of()).get("items").asInt()).isEqualTo(48);
    }

    @Test
    @Order(2)
    void atpQuoteAndRawJdbcFulfillmentQuoteAgreeOnStockOnHand() {
        for (JsonNode fc : get("/api/fulfillment-centers")) {
            String fcCode = fc.get("code").asString();
            JsonNode atp = get("/api/atp?sku=SOFA-3S-OAT&fc=" + fcCode + "&qty=1");
            JsonNode quote = post("/api/fulfillment/quote", Map.of("sku", "SOFA-3S-OAT", "fc", fcCode, "requestedQuantity", 1));
            int available = atp.get("availableNow").asInt();
            assertThat(quote.get("allocatedFromInventory").asInt()).isEqualTo(Math.min(available, 1));
            // both read paths must date the same inbound PO the same way (arrival + dock-to-stock)
            JsonNode big = post("/api/fulfillment/quote", Map.of("sku", "SOFA-3S-OAT", "fc", fcCode, "requestedQuantity", 100_000));
            for (JsonNode alloc : big.get("purchaseOrderAllocations")) {
                String po = alloc.get("poNumber").asString();
                for (JsonNode supply : atp.get("supplies")) {
                    if (supply.get("poNumber").asString().equals(po)) {
                        assertThat(alloc.get("expectedAt").asString()).isEqualTo(supply.get("arrives").asString());
                    }
                }
            }
            assertThat(atp.get("promisable").asBoolean()).isEqualTo(atp.hasNonNull("promiseDate"));
            assertThat(atp.get("timeline").size()).isGreaterThan(0);
        }
        JsonNode huge = post("/api/fulfillment/quote", Map.of("sku", "SOFA-3S-OAT", "requestedQuantity", 100_000));
        assertThat(huge.get("fullyFulfilled").asBoolean()).isFalse();
        assertThat(huge.get("fulfilledQuantity").asInt() + huge.get("remainingQuantity").asInt()).isEqualTo(100_000);
        assertThat(get("/api/atp/SOFA-3S-OAT?qty=2")).hasSize(4);
    }

    @Test
    @Order(3)
    void milestoneFlowsThroughKafkaIntoAPredictionAndReplaysAreIgnored() {
        long shipmentId = -1;
        for (JsonNode po : get("/api/purchase-orders?status=OPEN")) {
            String stage = po.get("currentStage").asString();
            if (po.hasNonNull("shipmentId") && (stage.equals("BOOKED") || stage.equals("DEPARTED_ORIGIN"))) {
                shipmentId = po.get("shipmentId").asLong();
                break;
            }
        }
        assertThat(shipmentId).isPositive();
        int milestonesBefore = get("/api/shipments/" + shipmentId).get("milestones").size();

        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = Map.of("type", "ARRIVED_DEST_PORT",
                "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(), "source", "CARRIER_EDI", "eventId", eventId.toString());
        assertThat(post("/api/shipments/" + shipmentId + "/milestones", body).get("accepted").asBoolean()).isTrue();

        final long id = shipmentId;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode s = get("/api/shipments/" + id).get("shipment");
            assertThat(s.get("currentStage").asString()).isEqualTo("ARRIVED_DEST_PORT");
            assertThat(s.hasNonNull("predictedArrival")).isTrue();
            assertThat(s.get("predictedConfidence").asString()).isEqualTo("LOW");   // no lane history yet
            assertThat(s.get("predictionBasis").asString()).contains("No lane history");
        });

        // the second consumer of shipment.eta-updated re-projects the SKUs on that container at its destination FC
        String destFc = get("/api/shipments/" + shipmentId).get("lane").get("destFcCode").asString();
        int batchesBefore = sink.batches.size();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(sink.batches).anySatisfy(b -> {
                    assertThat(b.getKey()).isEqualTo("eta-updated");
                    assertThat(b.getValue()).isNotEmpty().allSatisfy(i -> assertThat(i.fc()).isEqualTo(destFc));
                    assertThat(b.getValue()).extracting(AvailabilityItem::source).containsOnly("eta-updated");
                }));

        assertThat(post("/api/shipments/" + shipmentId + "/milestones", body).get("accepted").asBoolean()).isFalse();
        assertThat(get("/api/shipments/" + shipmentId).get("milestones")).hasSize(milestonesBefore + 1);

        // dbt would normally write this; simulate a refreshed lane statistic and re-score
        JsonNode lane = get("/api/shipments/" + shipmentId).get("lane");
        jdbc.sql("""
                insert into analytics.lane_lead_time_stats (origin_port, dest_fc_code, from_stage, to_stage, p50_days, p80_days, sample_n)
                values (:o, :d, 'ARRIVED_DEST_PORT', 'RECEIVED_FC', 4.0, 6.5, 50)
                on conflict (origin_port, dest_fc_code, from_stage, to_stage) do update set p80_days = excluded.p80_days, sample_n = excluded.sample_n
                """)
                .param("o", lane.get("originPort").asString())
                .param("d", lane.get("destFcCode").asString())
                .update();
        JsonNode recalc = post("/api/eta/recalculate-all", Map.of());
        assertThat(recalc.get("shipments").asInt()).isGreaterThan(0);

        JsonNode after = get("/api/shipments/" + shipmentId).get("shipment");
        assertThat(after.get("predictedConfidence").asString()).isEqualTo("HIGH");
        assertThat(after.get("predictionBasis").asString()).contains("P80 of 50 shipments");
        assertThat(get("/api/lanes/stats")).hasSize(1);
        assertThat(get("/api/exceptions").isArray()).isTrue();
    }
}
