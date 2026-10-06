package com.haoyu.inbound.planning;

import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order-up-to replenishment on weeks of cover, per SKU x FC:
 * <pre>
 *   weekly demand = units ordered in the last 28 days / 4   (rejected orders count: that demand was real)
 *   position      = available now + inbound outstanding - backorder commitments
 *   cover (weeks) = position / weekly demand
 *   if cover < reorder point: order (target weeks x weekly demand - position), rounded up to cases of 10
 * </pre>
 * Ocean lead time is 4-7 weeks door to door, so the defaults reorder at 8 weeks and order up to 14.
 */
@Service
public class ReplenishmentService {

    static final int CASE_PACK = 10;

    public record Suggestion(String sku, String fc, int unitsLast28d, double weeklyDemand, int availableNow, int inbound,
                             int backorders, int position, double weeksOfCover, int suggestedQty) {}

    private record Row(String sku, String fc, int unitsLast28d, int availableNow, int inbound, int backorders) {}

    private final JdbcClient jdbc;

    public ReplenishmentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Suggestion> suggest(double reorderWeeks, double targetWeeks) {
        if (reorderWeeks <= 0 || targetWeeks < reorderWeeks) throw new IllegalArgumentException("need 0 < reorderWeeks <= targetWeeks");
        List<Row> rows = jdbc.sql("""
                with demand as (
                    select sku_id, fc_id, sum(qty) as units
                    from customer_order
                    where created_at >= now() - interval '28 days' and status <> 'CANCELLED'
                    group by sku_id, fc_id
                ), inbound as (
                    select l.sku_id, po.dest_fc_id as fc_id, sum(l.qty_ordered - l.qty_received) as units
                    from purchase_order_line l join purchase_order po on po.id = l.po_id
                    where po.status = 'OPEN'
                    group by l.sku_id, po.dest_fc_id
                ), backlog as (
                    select sku_id, fc_id, sum(qty) as units from demand_commitment group by sku_id, fc_id
                )
                select sku.code as sku, fc.code as fc,
                       coalesce(d.units, 0) as units_last28d,
                       ip.on_hand - ip.reserved as available_now,
                       coalesce(i.units, 0) as inbound,
                       coalesce(b.units, 0) as backorders
                from inventory_position ip
                join sku on sku.id = ip.sku_id
                join fulfillment_center fc on fc.id = ip.fc_id
                left join demand d on d.sku_id = ip.sku_id and d.fc_id = ip.fc_id
                left join inbound i on i.sku_id = ip.sku_id and i.fc_id = ip.fc_id
                left join backlog b on b.sku_id = ip.sku_id and b.fc_id = ip.fc_id
                """)
                .query(Row.class)
                .list();
        return rows.stream()
                .map(r -> evaluate(r.sku(), r.fc(), r.unitsLast28d(), r.availableNow(), r.inbound(), r.backorders(), reorderWeeks, targetWeeks))
                .sorted(Comparator.comparingDouble(Suggestion::weeksOfCover))
                .toList();
    }

    /** Pure policy, unit-tested on its own. */
    static Suggestion evaluate(String sku, String fc, int unitsLast28d, int availableNow, int inbound, int backorders,
                               double reorderWeeks, double targetWeeks) {
        double weekly = unitsLast28d / 4.0;
        int position = availableNow + inbound - backorders;
        double cover = weekly == 0 ? Double.POSITIVE_INFINITY : position / weekly;
        int qty = 0;
        if (cover < reorderWeeks) {
            double need = targetWeeks * weekly - position;
            qty = (int) (Math.ceil(need / CASE_PACK) * CASE_PACK);
        }
        return new Suggestion(sku, fc, unitsLast28d, weekly, availableNow, inbound, backorders, position,
                Double.isInfinite(cover) ? 999 : Math.round(cover * 10) / 10.0, Math.max(qty, 0));
    }
}
