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

    OrderController(OrderService service, OrderRepository orders, BackorderAllocator allocator, com.haoyu.inbound.common.AppProperties props) {
        this.service = service;
        this.orders = orders;
        this.allocator = allocator;
        this.props = props;
    }

    record PlaceOrderRequest(@NotBlank String orderRef, Channel channel, @NotBlank String sku, String fc,
                             @Positive int qty, LocalDate needBy) {}

    /** Storefront / B2B / store systems: 201 for a new order, 200 when the orderRef was already placed. */
    @PostMapping("/api/internal/orders")
    ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest r) {
        return respond(service.place(new OrderService.PlaceOrder(r.orderRef(), r.channel(), r.sku(), r.fc(), r.qty(), r.needBy())));
    }

    record VisitorOrderRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "VISIT-[A-Za-z0-9-]{6,40}") String orderRef,
                               @NotBlank String sku, String fc, @Positive int qty) {}

    /**
     * A visitor trying the demo: an online order of at most a few units, labelled VISIT-..., going
     * through exactly the same decision as every other order.
     */
    @PostMapping("/api/orders")
    ResponseEntity<OrderView> placeAsVisitor(@Valid @RequestBody VisitorOrderRequest r) {
        if (r.qty() > props.orders().publicMaxQty()) {
            throw new IllegalArgumentException("visitors can order at most " + props.orders().publicMaxQty() + " units");
        }
        return respond(service.place(new OrderService.PlaceOrder(r.orderRef(), Channel.ONLINE, r.sku(), r.fc(), r.qty(), null)));
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
