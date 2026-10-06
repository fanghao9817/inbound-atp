package com.haoyu.inbound.procurement;

import com.haoyu.inbound.common.ConflictException;
import com.haoyu.inbound.common.NotFoundException;
import com.haoyu.inbound.events.AvailabilityEvents;
import com.haoyu.inbound.inventory.InventoryService;
import com.haoyu.inbound.inventory.InventoryService.Ref;
import com.haoyu.inbound.orders.BackorderAllocator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods receipt (putaway): the warehouse has counted the container and put the stock away. Happens
 * receiving_buffer_days after the RECEIVED_FC gate-in milestone - the dock-to-stock time ATP already
 * promises with - so stock becomes sellable exactly when ATP said it would. In one transaction: PO
 * lines get received and damaged counts, sellable units go into stock (ledger row each), the PO is
 * closed (short shipments are closed short, as a buyer would), and the stock is offered to due
 * commitments straight away. Idempotent on the WMS event id.
 */
@Service
public class ReceivingService {

    public enum Outcome { POSTED, DUPLICATE }

    public record Receipt(long receiptId, long shipmentId, Outcome outcome, int unitsReceived, int unitsDamaged) {}

    private record Line(long lineId, long poId, String poStatus, long skuId, String skuCode, long fcId, String fcCode, int outstanding) {}

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final BackorderAllocator allocator;
    private final AvailabilityEvents availability;
    private final ShipmentRepository shipments;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReceivingService(JdbcClient jdbc, InventoryService inventory, BackorderAllocator allocator, AvailabilityEvents availability,
                            ShipmentRepository shipments, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.allocator = allocator;
        this.availability = availability;
        this.shipments = shipments;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public Receipt receive(long shipmentId, UUID eventId, OffsetDateTime receivedAt, List<ReceivedLine> counted) {
        if (receivedAt.isAfter(OffsetDateTime.now(clock).plus(ShipmentService.MAX_CLOCK_SKEW))) {
            throw new IllegalArgumentException("receivedAt " + receivedAt + " is in the future");
        }
        Shipment shipment = shipments.find(shipmentId).orElseThrow(() -> new NotFoundException("shipment", shipmentId));
        Long receiptId = jdbc.sql("""
                insert into goods_receipt (shipment_id, event_id, received_at, units_received, units_damaged)
                values (:shipment, :event, :at, 0, 0)
                on conflict do nothing
                returning id
                """)
                .param("shipment", shipmentId).param("event", eventId).param("at", receivedAt)
                .query(Long.class).optional().orElse(null);
        if (receiptId == null) {
            var existing = jdbc.sql("select id, event_id, units_received, units_damaged from goods_receipt where shipment_id = :s or event_id = :e")
                    .param("s", shipmentId).param("e", eventId).query().singleRow();
            if (eventId.equals(existing.get("event_id"))) {
                return new Receipt(((Number) existing.get("id")).longValue(), shipmentId, Outcome.DUPLICATE,
                        ((Number) existing.get("units_received")).intValue(), ((Number) existing.get("units_damaged")).intValue());
            }
            throw new ConflictException("shipment " + shipmentId + " already has a goods receipt under another event id");
        }
        if (shipment.currentStage() != MilestoneType.RECEIVED_FC) {
            throw new ConflictException("shipment " + shipmentId + " is " + shipment.currentStage() + "; record RECEIVED_FC (gate-in) before the putaway");
        }

        Map<String, ReceivedLine> bySku = counted == null ? Map.of()
                : counted.stream().filter(c -> c.sku() != null).collect(Collectors.toMap(ReceivedLine::sku, Function.identity(), (a, b) -> b));
        // sku order = the order positions are locked in, so two receipts at one FC cannot deadlock
        List<Line> lines = jdbc.sql("""
                select l.id as line_id, po.id as po_id, po.status as po_status, l.sku_id, sku.code as sku_code,
                       po.dest_fc_id as fc_id, fc.code as fc_code, l.qty_ordered - l.qty_received as outstanding
                from shipment s
                join purchase_order po on po.id = s.po_id
                join purchase_order_line l on l.po_id = po.id
                join sku on sku.id = l.sku_id
                join fulfillment_center fc on fc.id = po.dest_fc_id
                where s.id = :id
                order by l.sku_id
                """)
                .param("id", shipmentId)
                .query(Line.class)
                .list();
        if (lines.isEmpty() || !"OPEN".equals(lines.getFirst().poStatus())) {
            throw new ConflictException("the purchase order of shipment " + shipmentId + " is not open");
        }
        int totalReceived = 0, totalDamaged = 0;
        for (Line line : lines) {
            if (line.outstanding() <= 0) continue;
            ReceivedLine c = bySku.get(line.skuCode());
            int received = c == null || c.qtyReceived() == null ? line.outstanding() : clamp(c.qtyReceived(), 0, line.outstanding());
            int damaged = c == null || c.qtyDamaged() == null ? 0 : clamp(c.qtyDamaged(), 0, received);
            jdbc.sql("update purchase_order_line set qty_received = qty_received + :r, qty_damaged = qty_damaged + :d where id = :id")
                    .param("r", received).param("d", damaged).param("id", line.lineId())
                    .update();
            inventory.lock(line.skuId(), line.fcId());
            inventory.receive(line.skuId(), line.fcId(), received - damaged, new Ref("RECEIPT", receiptId));
            totalReceived += received;
            totalDamaged += damaged;
            meters.counter("receipts.units", "fc", line.fcCode()).increment(received - damaged);
            if (damaged > 0) meters.counter("receipts.damaged.units", "fc", line.fcCode()).increment(damaged);
        }
        jdbc.sql("update goods_receipt set units_received = :r, units_damaged = :d where id = :id")
                .param("r", totalReceived).param("d", totalDamaged).param("id", receiptId).update();
        jdbc.sql("update purchase_order set status = 'RECEIVED' where id = :id and status = 'OPEN'").param("id", lines.getFirst().poId()).update();
        for (Line line : lines) {
            allocator.allocate(line.skuId(), line.fcId());
            availability.changed(line.skuCode(), line.fcCode(), "goods received");
        }
        return new Receipt(receiptId, shipmentId, Outcome.POSTED, totalReceived, totalDamaged);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
