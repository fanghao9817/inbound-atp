package com.haoyu.inbound.orders;

import com.haoyu.inbound.atp.AtpService;
import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import com.haoyu.inbound.catalog.Sku;
import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.common.NotFoundException;
import com.haoyu.inbound.events.AvailabilityEvents;
import com.haoyu.inbound.inventory.InventoryService;
import com.haoyu.inbound.inventory.InventoryService.Ref;
import com.haoyu.inbound.orders.OrderPolicy.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    public record PlaceOrder(String orderRef, Channel channel, Origin origin, String sku, String fc, int qty, LocalDate needBy) {}

    /** @param created false when the order_ref already existed (idempotent replay) */
    public record PlaceResult(OrderView order, boolean created) {}

    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final AtpService atp;
    private final InventoryService inventory;
    private final AvailabilityEvents availability;
    private final AppProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate readCommitted;

    public OrderService(OrderRepository orders, CatalogRepository catalog, AtpService atp, InventoryService inventory,
                        AvailabilityEvents availability, AppProperties props, Clock clock,
                        MeterRegistry meters, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.catalog = catalog;
        this.atp = atp;
        this.inventory = inventory;
        this.availability = availability;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        this.readCommitted = new TransactionTemplate(txManager);
        // READ COMMITTED + SELECT ... FOR UPDATE on the position: once we hold the lock, every following
        // statement sees the latest committed state of that position (a REPEATABLE READ snapshot taken
        // before the lock could be stale, and Postgres would abort the update instead).
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Places an order idempotently (same orderRef = same order). Without an FC the order goes to the
     * FC with the earliest promise for that quantity. The decision itself is made under the position
     * lock, so two concurrent orders for the last units of a SKU cannot both get them.
     */
    public PlaceResult place(PlaceOrder cmd) {
        if (cmd.orderRef() == null || cmd.orderRef().isBlank()) throw new IllegalArgumentException("orderRef is required");
        if (cmd.qty() <= 0) throw new IllegalArgumentException("qty must be positive");
        Optional<OrderView> existing = orders.findViewByRef(cmd.orderRef());
        if (existing.isPresent()) return new PlaceResult(existing.get(), false);

        Sku sku = catalog.requireSku(cmd.sku());
        FulfillmentCenter fc = cmd.fc() != null && !cmd.fc().isBlank() ? catalog.requireFc(cmd.fc()) : bestFc(sku, cmd.qty());
        try {
            Long id = readCommitted.execute(status -> placeAt(sku, fc, cmd));
            return new PlaceResult(orders.findView(id).orElseThrow(), true);
        } catch (DuplicateKeyException sameRefRace) {
            return new PlaceResult(orders.findViewByRef(cmd.orderRef()).orElseThrow(), false);
        }
    }

    private long placeAt(Sku sku, FulfillmentCenter fc, PlaceOrder cmd) {
        LocalDate today = LocalDate.now(clock);
        inventory.lock(sku.id(), fc.id());
        var inputs = atp.inputs(sku.id(), fc, today, null);
        Optional<LocalDate> earliest = atp.timeline(inputs, fc, today).earliestDateFor(cmd.qty());
        Decision d = OrderPolicy.decide(cmd.needBy(), today, earliest, props.orders().allocationWindowDays());
        Channel channel = cmd.channel() == null ? Channel.ONLINE : cmd.channel();

        OrderStatus status = switch (d.action()) {
            case RESERVE -> OrderStatus.RESERVED;
            case SCHEDULE -> OrderStatus.SCHEDULED;
            case BACKORDER -> OrderStatus.BACKORDERED;
            case REJECT -> OrderStatus.REJECTED;
        };
        Origin origin = cmd.origin() == null ? Origin.FEED : cmd.origin();
        long id = orders.insert(cmd.orderRef(), channel, origin, sku.id(), fc.id(), cmd.qty(), status, d.promiseDate(), cmd.needBy(),
                status == OrderStatus.RESERVED);
        if (status == OrderStatus.RESERVED) {
            inventory.reserve(sku.id(), fc.id(), cmd.qty(), new Ref("ORDER", id));
        }
        // SCHEDULED and BACKORDERED need no second write: the demand_commitment view is those orders
        if (status != OrderStatus.REJECTED) {
            availability.changed(sku.code(), fc.code(), "order " + status.name().toLowerCase());
        }
        meters.counter("orders.placed", "channel", channel.name(), "origin", origin.name(), "status", status.name()).increment();
        meters.counter("orders.units", "channel", channel.name(), "origin", origin.name(), "status", status.name()).increment(cmd.qty());
        return id;
    }

    private FulfillmentCenter bestFc(Sku sku, int qty) {
        var best = atp.summary(sku.code(), qty).stream()
                .filter(AtpService.FcSummary::promisable)
                .min(Comparator.comparing(AtpService.FcSummary::promiseDate)
                        .thenComparing(AtpService.FcSummary::availableNow, Comparator.reverseOrder()))
                .map(AtpService.FcSummary::fc)
                .orElseGet(() -> catalog.listFcs().getFirst().code());
        return catalog.requireFc(best);
    }

    /** Visitor orders hold stock for an hour at most (like a cart hold); then they are cancelled. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    void expireVisitorOrders() {
        for (long id : orders.expiredVisitorOrders()) {
            try {
                // a self-call bypasses the @Transactional proxy on cancel(), so open the transaction explicitly
                readCommitted.executeWithoutResult(status -> cancel(id));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(OrderService.class).warn("could not expire visitor order {}: {}", id, e.toString());
            }
        }
    }

    /** Picks and ships a reserved order. Shipping an already shipped order is a no-op. */
    @Transactional
    public OrderView ship(long id) {
        CustomerOrder seen = orders.find(id).orElseThrow(() -> new NotFoundException("order", id));
        inventory.lock(seen.skuId(), seen.fcId());                      // lock order: position, then order
        CustomerOrder o = orders.lock(id).orElseThrow();
        if (o.status() == OrderStatus.SHIPPED) return orders.findView(id).orElseThrow();
        if (o.status() != OrderStatus.RESERVED) throw new OrderConflictException("order " + id + " is " + o.status() + ", only RESERVED orders can ship");
        inventory.ship(o.skuId(), o.fcId(), o.qty(), new Ref("ORDER", id));
        orders.markShipped(id);
        availability.changed(catalog.requireSku(o.skuId()).code(), catalog.requireFc(o.fcId()).code(), "order shipped");
        meters.counter("orders.shipped.units").increment(o.qty());
        return orders.findView(id).orElseThrow();
    }

    /** Releases the reservation, or drops the commitment (by leaving SCHEDULED/BACKORDERED). Cancelling twice is a no-op. */
    @Transactional
    public OrderView cancel(long id) {
        CustomerOrder seen = orders.find(id).orElseThrow(() -> new NotFoundException("order", id));
        inventory.lock(seen.skuId(), seen.fcId());
        CustomerOrder o = orders.lock(id).orElseThrow();
        switch (o.status()) {
            case CANCELLED -> { return orders.findView(id).orElseThrow(); }
            case RESERVED -> inventory.release(o.skuId(), o.fcId(), o.qty(), new Ref("ORDER", id));
            case SCHEDULED, BACKORDERED -> { }
            default -> throw new OrderConflictException("order " + id + " is " + o.status() + " and cannot be cancelled");
        }
        orders.markCancelled(id);
        availability.changed(catalog.requireSku(o.skuId()).code(), catalog.requireFc(o.fcId()).code(), "order cancelled");
        meters.counter("orders.cancelled").increment();
        return orders.findView(id).orElseThrow();
    }
}
