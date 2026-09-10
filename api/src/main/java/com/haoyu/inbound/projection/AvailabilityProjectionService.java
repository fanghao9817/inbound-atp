package com.haoyu.inbound.projection;

import com.haoyu.inbound.atp.AtpService;
import com.haoyu.inbound.atp.AtpService.AtpQuote;
import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Turns the live ATP answer into storefront rows. A full refresh covers every SKU x FC; an
 * ETA change only re-projects the SKUs on that container at its destination FC.
 */
@Service
public class AvailabilityProjectionService {

    public record Summary(int items, String source) {}

    private final AtpService atp;
    private final CatalogRepository catalog;
    private final JdbcClient jdbc;
    private final ProjectionSink sink;
    private final Clock clock;

    public AvailabilityProjectionService(AtpService atp, CatalogRepository catalog, JdbcClient jdbc,
                                         ProjectionSink sink, Clock clock) {
        this.atp = atp;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.sink = sink;
        this.clock = clock;
    }

    public Summary projectAll() {
        List<FulfillmentCenter> fcs = catalog.listFcs();
        List<AvailabilityItem> items = catalog.listSkus().stream()
                .flatMap(sku -> fcs.stream().map(fc -> item(sku.code(), fc, "full-refresh")))
                .toList();
        sink.push("full-refresh", items);
        return new Summary(items.size(), "full-refresh");
    }

    /** SKUs on the shipment's purchase order, at the PO's destination FC. */
    public Summary projectForShipment(long shipmentId) {
        record Affected(String skuCode, String fcCode) {}
        List<Affected> affected = jdbc.sql("""
                select sku.code as sku_code, fc.code as fc_code
                from shipment s
                join purchase_order po on po.id = s.po_id
                join fulfillment_center fc on fc.id = po.dest_fc_id
                join purchase_order_line l on l.po_id = po.id
                join sku on sku.id = l.sku_id
                where s.id = :id
                order by sku.code
                """)
                .param("id", shipmentId)
                .query(Affected.class)
                .list();
        if (affected.isEmpty()) return new Summary(0, "eta-updated");
        FulfillmentCenter fc = catalog.requireFc(affected.getFirst().fcCode());
        List<AvailabilityItem> items = affected.stream().map(a -> item(a.skuCode(), fc, "eta-updated")).toList();
        sink.push("eta-updated", items);
        return new Summary(items.size(), "eta-updated");
    }

    private AvailabilityItem item(String skuCode, FulfillmentCenter fc, String source) {
        AtpQuote q = atp.quote(skuCode, fc.code(), 1);
        var next = q.supplies().isEmpty() ? null : q.supplies().getFirst();
        return new AvailabilityItem(skuCode, fc.code(), fc.name(), q.availableNow(), q.promiseDate(), q.promisable(),
                next == null ? null : next.confidence(), next == null ? null : next.arrives(),
                OffsetDateTime.now(clock), source);
    }
}
