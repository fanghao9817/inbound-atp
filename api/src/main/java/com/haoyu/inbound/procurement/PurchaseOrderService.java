package com.haoyu.inbound.procurement;

import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import com.haoyu.inbound.catalog.Sku;
import com.haoyu.inbound.eta.LaneStatsRepository;
import com.haoyu.inbound.events.AvailabilityEvents;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a purchase order with its container (BOOKED) - what a buyer does when replenishment says
 * stock will run short. The carrier's planned arrival is quoted from the lane's median transit plus a
 * few days of padding; our own prediction then takes over from the BOOKED milestone recorded here.
 */
@Service
public class PurchaseOrderService {

    /**
     * Days a carrier pads its quote on top of the lane median (departure to FC): enough that about 87% of
     * containers arrive by the plan under the simulated transit (departure slips, port delays, congestion,
     * dock hours) - the same share the seeded history shows.
     */
    static final int CARRIER_PADDING_DAYS = 7;
    static final int DEFAULT_TRANSIT_DAYS = 30;

    public record NewLine(String sku, int qty) {}

    public record CreatePurchaseOrder(String clientRef, String supplier, String originPort, String destFc,
                                      LocalDate plannedDeparture, String carrier, List<NewLine> lines) {}

    public record Created(long poId, String poNumber, long shipmentId, LocalDate plannedArrival, boolean created) {}

    private final JdbcClient jdbc;
    private final CatalogRepository catalog;
    private final LaneStatsRepository laneStats;
    private final ShipmentService shipments;
    private final AvailabilityEvents availability;
    private final Clock clock;

    public PurchaseOrderService(JdbcClient jdbc, CatalogRepository catalog, LaneStatsRepository laneStats,
                                ShipmentService shipments, AvailabilityEvents availability, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.laneStats = laneStats;
        this.shipments = shipments;
        this.availability = availability;
        this.clock = clock;
    }

    /** Idempotent on clientRef: the same request twice returns the PO created the first time. */
    @Transactional
    public Created create(CreatePurchaseOrder cmd) {
        if (cmd.lines() == null || cmd.lines().isEmpty()) throw new IllegalArgumentException("a purchase order needs at least one line");
        if (cmd.supplier() == null || cmd.supplier().isBlank()) throw new IllegalArgumentException("supplier is required");
        if (cmd.originPort() == null || !cmd.originPort().matches("[A-Z]{5}")) throw new IllegalArgumentException("originPort must be a UN/LOCODE like VNSGN");
        if (cmd.clientRef() != null) {
            var existing = jdbc.sql("""
                    select po.id as po_id, po.po_number, s.id as shipment_id, po.planned_arrival, false as created
                    from purchase_order po join shipment s on s.po_id = po.id where po.client_ref = :ref
                    """).param("ref", cmd.clientRef()).query(Created.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        FulfillmentCenter fc = catalog.requireFc(cmd.destFc());
        List<Sku> skus = cmd.lines().stream().map(l -> {
            if (l.qty() <= 0) throw new IllegalArgumentException("line qty must be positive");
            return catalog.requireSku(l.sku());
        }).toList();

        LocalDate today = LocalDate.now(clock);
        LocalDate departure = cmd.plannedDeparture() != null ? cmd.plannedDeparture() : today.plusDays(7);
        int transit = laneStats.find(cmd.originPort(), fc.code(), MilestoneType.DEPARTED_ORIGIN, MilestoneType.RECEIVED_FC)
                .map(s -> (int) Math.ceil(s.p50Days().doubleValue()))
                .orElse(DEFAULT_TRANSIT_DAYS);
        LocalDate plannedArrival = departure.plusDays(transit + CARRIER_PADDING_DAYS);

        String poNumber = "PO-" + jdbc.sql("select nextval('purchase_order_number_seq')").query(Long.class).single();
        long poId = jdbc.sql("""
                insert into purchase_order (po_number, supplier, origin_port, dest_fc_id, status, planned_arrival, client_ref)
                values (:po, :supplier, :origin, :fc, 'OPEN', :arrival, :ref)
                returning id
                """)
                .param("po", poNumber).param("supplier", cmd.supplier()).param("origin", cmd.originPort())
                .param("fc", fc.id()).param("arrival", plannedArrival).param("ref", cmd.clientRef())
                .query(Long.class).single();
        for (int i = 0; i < skus.size(); i++) {
            jdbc.sql("insert into purchase_order_line (po_id, sku_id, qty_ordered) values (:po, :sku, :qty)")
                    .param("po", poId).param("sku", skus.get(i).id()).param("qty", cmd.lines().get(i).qty())
                    .update();
        }
        long shipmentId = jdbc.sql("""
                insert into shipment (po_id, carrier, planned_departure, planned_arrival)
                values (:po, :carrier, :departure, :arrival)
                returning id
                """)
                .param("po", poId).param("carrier", cmd.carrier() == null ? "Maersk" : cmd.carrier())
                .param("departure", departure).param("arrival", plannedArrival)
                .query(Long.class).single();
        // the booking confirmation is the first milestone; it starts our own ETA prediction
        UUID bookedEvent = UUID.nameUUIDFromBytes(("booked:" + poNumber).getBytes(StandardCharsets.UTF_8));
        shipments.recordMilestone(shipmentId, MilestoneType.BOOKED, OffsetDateTime.now(clock), MilestoneSource.BUYER, bookedEvent);
        skus.forEach(s -> availability.changed(s.code(), fc.code(), "purchase order placed"));
        return new Created(poId, poNumber, shipmentId, plannedArrival, true);
    }
}
