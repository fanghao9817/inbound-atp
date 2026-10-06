package com.haoyu.inboundsim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The simulator's only view of the world: the same HTTP API every other client uses. Reads are public
 * endpoints; writes go to /api/internal/** with the shared token. No database access.
 */
@Component
public class ApiClient {

    public record Line(String skuCode, int qtyOrdered, int qtyReceived) {}

    public record PurchaseOrder(long id, String poNumber, String supplier, String originPort, String destFc, String status,
                                Long shipmentId, String currentStage, List<Line> lines) {}

    public record Milestone(String type, OffsetDateTime occurredAt, OffsetDateTime recordedAt, String source) {}

    public record Lane(String originPort, String destFcCode, int receivingBufferDays) {}

    public record ShipmentView(Map<String, Object> shipment, Lane lane, List<Milestone> milestones) {}

    public record Order(long id, String orderRef, String channel, String origin, String sku, String fc, int qty, String status,
                        LocalDate promiseDate, LocalDate needBy, OffsetDateTime createdAt, OffsetDateTime reservedAt) {}

    public record Quote(int availableNow, LocalDate promiseDate, boolean promisable) {}

    public record ProposedLine(String sku, int qty) {}

    public record Proposal(String clientRef, String supplier, String originPort, String destFc, List<ProposedLine> lines) {}

    public record Plan(List<Proposal> proposals) {}

    private final RestClient http;

    public ApiClient(SimProperties props, RestClient.Builder builder) {
        this.http = builder.baseUrl(props.apiBaseUrl())
                .defaultHeader("X-Internal-Token", props.token() == null ? "" : props.token())
                .defaultStatusHandler(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new IllegalStateException("API " + res.getStatusCode() + " for " + req.getURI().getPath());
                })
                .build();
    }

    public List<PurchaseOrder> openPurchaseOrders() {
        return List.of(http.get().uri("/api/purchase-orders?status=OPEN&limit=1000").retrieve().body(PurchaseOrder[].class));
    }

    public ShipmentView shipment(long id) {
        return http.get().uri("/api/shipments/{id}", id).retrieve().body(ShipmentView.class);
    }

    public Quote quote(String sku, String fc, int qty) {
        return http.get().uri("/api/atp?sku={s}&fc={f}&qty={q}", sku, fc, qty).retrieve().body(Quote.class);
    }

    public List<Order> orders(String status) {
        return List.of(http.get().uri("/api/orders?status={s}&limit=500", status).retrieve().body(Order[].class));
    }

    public Plan replenishment() {
        return http.get().uri("/api/planning/replenishment").retrieve().body(Plan.class);
    }

    /** @return HTTP status: 202 recorded or replayed, 409 conflicting, 400 rejected */
    public int milestone(long shipmentId, Stage type, Instant occurredAt, String source, UUID eventId) {
        return post("/api/internal/shipments/" + shipmentId + "/milestones", Map.of(
                "type", type.name(), "occurredAt", occurredAt.toString(), "source", source, "eventId", eventId.toString()));
    }

    public int receipt(long shipmentId, UUID eventId, Instant receivedAt, List<Map<String, Object>> lines) {
        return post("/api/internal/shipments/" + shipmentId + "/receipt", Map.of(
                "eventId", eventId.toString(), "receivedAt", receivedAt.toString(), "lines", lines));
    }

    public int order(String ref, String channel, String sku, String fc, int qty, LocalDate needBy) {
        Map<String, Object> body = new HashMap<>(Map.of("orderRef", ref, "channel", channel, "sku", sku, "fc", fc, "qty", qty));
        if (needBy != null) body.put("needBy", needBy.toString());
        return post("/api/internal/orders", body);
    }

    public int ship(long orderId) {
        return post("/api/internal/orders/" + orderId + "/ship", Map.of());
    }

    public int cancel(long orderId) {
        return post("/api/internal/orders/" + orderId + "/cancel", Map.of());
    }

    public int purchaseOrder(Proposal p, LocalDate plannedDeparture, String carrier) {
        return post("/api/internal/purchase-orders", Map.of(
                "clientRef", p.clientRef(), "supplier", p.supplier(), "originPort", p.originPort(), "destFc", p.destFc(),
                "plannedDeparture", plannedDeparture.toString(), "carrier", carrier,
                "lines", p.lines().stream().map(l -> Map.of("sku", l.sku(), "qty", l.qty())).toList()));
    }

    public int note(String ref, String kind, String message) {
        return post("/api/internal/notes", Map.of("ref", ref, "kind", kind, "message", message));
    }

    private int post(String path, Object body) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> res.getStatusCode().value());
    }
}
