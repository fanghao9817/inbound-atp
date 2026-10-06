package com.haoyu.inbound.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final OrderService service;
    private final OrderRepository orders;
    private final BackorderAllocator allocator;
    private final com.haoyu.inbound.common.AppProperties props;
    private final java.time.Clock clock;

    static final int VISITOR_DAILY_CAP = 200;

    OrderController(OrderService service, OrderRepository orders, BackorderAllocator allocator,
                    com.haoyu.inbound.common.AppProperties props, java.time.Clock clock) {
        this.service = service;
        this.orders = orders;
        this.allocator = allocator;
        this.props = props;
        this.clock = clock;
    }

    record PlaceOrderRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z0-9-]{1,40}") String orderRef,
                             Channel channel, @NotBlank String sku, String fc, @Positive int qty, LocalDate needBy) {}

    /** Storefront / B2B / store systems: 201 for a new order, 200 when the orderRef was already placed. */
    @PostMapping("/api/internal/orders")
    ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest r) {
        return respond(service.place(new OrderService.PlaceOrder(r.orderRef(), r.channel(), Origin.FEED, r.sku(), r.fc(), r.qty(), r.needBy())));
    }

    record VisitorOrderRequest(@NotBlank String sku, String fc, @Positive int qty) {}

    /**
     * A visitor trying the demo: an online order of a few units that goes through exactly the same
     * decision as every other order. The reference is generated here, visitor orders are capped per
     * day, excluded from the KPIs, and cancelled after an hour so they cannot drain stock.
     */
    @PostMapping("/api/orders")
    ResponseEntity<OrderView> placeAsVisitor(@Valid @RequestBody VisitorOrderRequest r) {
        if (r.qty() > props.orders().publicMaxQty()) {
            throw new IllegalArgumentException("visitors can order at most " + props.orders().publicMaxQty() + " units");
        }
        var since = java.time.LocalDate.now(clock).atStartOfDay(clock.getZone()).toOffsetDateTime();
        if (orders.visitorOrdersSince(since) >= VISITOR_DAILY_CAP) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String ref = "VISIT-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return respond(service.place(new OrderService.PlaceOrder(ref, Channel.ONLINE, Origin.VISITOR, r.sku(), r.fc(), r.qty(), null)));
    }

    private static ResponseEntity<OrderView> respond(OrderService.PlaceResult result) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @GetMapping("/api/orders")
    List<OrderView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                         @RequestParam(defaultValue = "100") int limit) {
        return orders.list(status == null ? null : status.toUpperCase(), channel == null ? null : channel.toUpperCase(),
                Math.min(Math.max(limit, 1), 500));
    }

    @PostMapping("/api/internal/orders/{id}/ship")
    OrderView ship(@PathVariable long id) {
        return service.ship(id);
    }

    @PostMapping("/api/internal/orders/{id}/cancel")
    OrderView cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    /** Runs the morning commitment run now (allocation + re-promise; also triggered after every receipt). */
    @PostMapping("/api/internal/allocation/run")
    BackorderAllocator.RunSummary allocate() {
        return allocator.allocateAll();
    }
}
