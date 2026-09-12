package com.haoyu.inbound.atp;

import com.haoyu.inbound.atp.AtpCalculator.Demand;
import com.haoyu.inbound.atp.AtpCalculator.Point;
import com.haoyu.inbound.atp.AtpCalculator.Result;
import com.haoyu.inbound.atp.AtpCalculator.Supply;
import com.haoyu.inbound.catalog.CatalogRepository;
import com.haoyu.inbound.catalog.FulfillmentCenter;
import com.haoyu.inbound.catalog.Sku;
import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.inventory.DemandCommitment;
import com.haoyu.inbound.inventory.InventoryPosition;
import com.haoyu.inbound.inventory.InventoryRepository;
import com.haoyu.inbound.procurement.InboundSupply;
import com.haoyu.inbound.procurement.PurchaseOrderRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AtpService {

    public record SupplyLine(String poNumber, int qty, LocalDate arrives, String stage, String confidence) {}

    public record DemandLine(String reference, int qty, LocalDate needBy) {}

    public record AtpQuote(String sku, String fc, int qty, int availableNow, LocalDate promiseDate, boolean promisable,
                           int horizonDays, List<Point> timeline, List<SupplyLine> supplies, List<DemandLine> demands) {}

    public record FcSummary(String fc, String fcName, int availableNow, LocalDate promiseDate, boolean promisable) {}

    private final CatalogRepository catalog;
    private final InventoryRepository inventory;
    private final PurchaseOrderRepository purchaseOrders;
    private final AppProperties props;
    private final Clock clock;

    public AtpService(CatalogRepository catalog, InventoryRepository inventory, PurchaseOrderRepository purchaseOrders,
                      AppProperties props, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.purchaseOrders = purchaseOrders;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Full explanation for one SKU at one FC: the timeline and every supply/demand line behind it.
     * REPEATABLE_READ so the inventory, inbound and demand queries all see one snapshot - the same
     * guarantee the plain-JDBC fulfillment path sets by hand.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AtpQuote quote(String skuCode, String fcCode, int qty) {
        requirePositive(qty);
        Sku sku = catalog.requireSku(skuCode);
        FulfillmentCenter fc = catalog.requireFc(fcCode);
        LocalDate today = LocalDate.now(clock);
        int horizon = props.atp().horizonDays();

        int availableNow = inventory.findPosition(sku.id(), fc.id()).map(InventoryPosition::availableNow).orElse(0);
        List<InboundSupply> inbound = purchaseOrders.openSupply(sku.id(), fc.id());
        List<DemandCommitment> commitments = inventory.listOpenCommitments(sku.id(), fc.id(), today.plusDays(horizon));

        List<Supply> supplies = inbound.stream()
                .map(s -> new Supply(s.effectiveArrival().plusDays(fc.receivingBufferDays()), s.qtyOutstanding(), s.poNumber()))
                .toList();
        List<Demand> demands = commitments.stream()
                .map(d -> new Demand(d.needBy(), d.qty(), d.reference()))
                .toList();
        Result result = AtpCalculator.compute(today, availableNow, supplies, demands, horizon);
        LocalDate promise = result.earliestDateFor(qty).orElse(null);

        return new AtpQuote(sku.code(), fc.code(), qty, availableNow, promise, promise != null, horizon, result.timeline(),
                inbound.stream().map(s -> new SupplyLine(s.poNumber(), s.qtyOutstanding(),
                        s.effectiveArrival().plusDays(fc.receivingBufferDays()), s.currentStage().name(), s.predictedConfidence())).toList(),
                commitments.stream().map(d -> new DemandLine(d.reference(), d.qty(), d.needBy())).toList());
    }

    /** One line per FC so a storefront can pick where to promise from. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<FcSummary> summary(String skuCode, int qty) {
        requirePositive(qty);
        catalog.requireSku(skuCode);
        return catalog.listFcs().stream().map(fc -> {
            AtpQuote q = quote(skuCode, fc.code(), qty);
            return new FcSummary(fc.code(), fc.name(), q.availableNow(), q.promiseDate(), q.promisable());
        }).toList();
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
    }
}
