package com.haoyu.inbound.projection;

import com.haoyu.inbound.atp.AtpService;
import com.haoyu.inbound.atp.AtpService.AtpQuote;
import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Turns the live ATP answer into storefront rows: one SKU x FC after an availability.changed event,
 * or every position in a full refresh.
 */
@Service
public class AvailabilityProjectionService {

    public record Summary(int items, String source) {}

    private final AtpService atp;
    private final CatalogRepository catalog;
    private final ProjectionSink sink;
    private final Clock clock;

    public AvailabilityProjectionService(AtpService atp, CatalogRepository catalog, ProjectionSink sink, Clock clock) {
        this.atp = atp;
        this.catalog = catalog;
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

    /** One SKU at one FC, after an availability.changed event. */
    public Summary projectPosition(String skuCode, String fcCode) {
        FulfillmentCenter fc = catalog.requireFc(fcCode);
        sink.push("availability-changed", List.of(item(skuCode, fc, "availability-changed")));
        return new Summary(1, "availability-changed");
    }

    private AvailabilityItem item(String skuCode, FulfillmentCenter fc, String source) {
        AtpQuote q = atp.quote(skuCode, fc.code(), 1);
        var next = q.supplies().isEmpty() ? null : q.supplies().getFirst();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new AvailabilityItem(skuCode, fc.code(), fc.name(), q.availableNow(), q.promiseDate(), q.promisable(),
                next == null ? null : next.confidence(), next == null ? null : next.arrives(),
                now, now.toInstant().toEpochMilli(), source);
    }
}
