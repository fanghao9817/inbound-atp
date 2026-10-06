package com.haoyu.inbound.orders;

import com.haoyu.inbound.atp.AtpService;
import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.events.AvailabilityEvents;
import com.haoyu.inbound.inventory.InventoryService;
import com.haoyu.inbound.inventory.InventoryService.Ref;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps open commitments honest at one SKU x FC, earliest due date first:
 * <ol>
 *   <li>allocate - a commitment that is due (inside the allocation window) becomes a reservation once
 *       ATP, computed with every OTHER commitment still in place, covers it today. A newer or smaller
 *       order therefore never takes stock an earlier commitment depends on.
 *   <li>re-promise - if inbound stock slipped so the promised date can no longer be met, the promise
 *       moves to the new earliest date (and an ops note records it), instead of silently going stale.
 * </ol>
 * Runs after every goods receipt (same transaction) and for all positions every morning at 06:00.
 */
@Service
public class BackorderAllocator {

    private static final Logger log = LoggerFactory.getLogger(BackorderAllocator.class);

    public record Result(int allocated, int repromised) {}

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final AtpService atp;
    private final CatalogRepository catalog;
    private final AvailabilityEvents availability;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public BackorderAllocator(OrderRepository orders, InventoryService inventory, AtpService atp, CatalogRepository catalog,
                              AvailabilityEvents availability, AppProperties props, Clock clock, TransactionTemplate tx,
                              JdbcClient jdbc, MeterRegistry meters) {
        this.orders = orders;
        this.inventory = inventory;
        this.atp = atp;
        this.catalog = catalog;
        this.availability = availability;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** One position; joins the caller's transaction (e.g. a goods receipt) or starts one. */
    @Transactional
    public Result allocate(long skuId, long fcId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate window = today.plusDays(props.orders().allocationWindowDays());
        FulfillmentCenter fc = catalog.requireFc(fcId);
        inventory.lock(skuId, fcId);
        int allocated = 0, repromised = 0;
        for (CustomerOrder o : orders.commitments(skuId, fcId)) {
            Optional<java.time.LocalDate> earliest = atp.timeline(atp.inputs(skuId, fc, today, o.id()), fc, today).earliestDateFor(o.qty());
            boolean due = o.needBy() == null || !o.needBy().isAfter(window);
            if (due && earliest.filter(d -> !d.isAfter(today)).isPresent()) {
                if (orders.markReservedFromCommitment(o.id()) == 0) continue;           // cancelled meanwhile
                inventory.reserve(skuId, fcId, o.qty(), new Ref("ORDER", o.id()));
                allocated++;
            } else if (earliest.isPresent() && o.promiseDate() != null && earliest.get().isAfter(o.promiseDate())) {
                if (orders.repromise(o.id(), earliest.get()) == 1) {
                    repromised++;
                    jdbc.sql("insert into ops_note (ref, kind, message) values (:ref, 'PROMISE', :msg) on conflict (ref) do nothing")
                            .param("ref", "repromise-" + o.id() + "-" + earliest.get())
                            .param("msg", "%s: promise moved %s → %s (inbound stock later than planned)"
                                    .formatted(o.orderRef(), o.promiseDate(), earliest.get()))
                            .update();
                }
            }
        }
        if (allocated + repromised > 0) {
            availability.changed(catalog.requireSku(skuId).code(), fc.code(), "commitments re-evaluated");
            meters.counter("orders.allocated").increment(allocated);
            meters.counter("orders.repromised").increment(repromised);
        }
        return new Result(allocated, repromised);
    }

    public record RunSummary(int positions, int allocated, int repromised) {}

    @Scheduled(cron = "0 0 6 * * *", zone = "America/Vancouver")
    public RunSummary allocateAll() {
        int positions = 0, allocated = 0, repromised = 0;
        for (var p : orders.positionsWithCommitments()) {
            Result r = tx.execute(s -> allocate(p.skuId(), p.fcId()));
            positions++;
            if (r != null) {
                allocated += r.allocated();
                repromised += r.repromised();
            }
        }
        log.info("commitment run: {} positions, {} allocated, {} re-promised", positions, allocated, repromised);
        return new RunSummary(positions, allocated, repromised);
    }
}
