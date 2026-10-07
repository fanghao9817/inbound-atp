package com.haoyu.inbound.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Operational numbers for the "Today" page, straight from the system of record.
 *
 * <p>Every time boundary is computed here from the injected business clock (Vancouver) and bound as a
 * parameter - SQL never decides what "today" means, so tests can pin the clock and DST days behave.
 * "Today" is compared with yesterday up to the same time of day, and "this week" (Monday to now)
 * with the same elapsed span of last week. Flow KPIs count FEED orders only (not seeded, migrated
 * or visitor orders).
 */
@Repository
public class DashboardRepository {

    public record Kpis(OffsetDateTime asOf, OffsetDateTime historySince,
                       int ordersToday, int ordersYesterdaySameTime, int unitsOrderedToday, int unitsOrderedYesterdaySameTime,
                       int ordersWtd, int ordersLastWtd,
                       int unitsShippedToday, int unitsShippedYesterdaySameTime, int unitsShippedWtd, int unitsShippedLastWtd,
                       int containersGatedInWtd, int containersGatedInLastWtd, int unitsReceivedWtd, int unitsReceivedLastWtd,
                       int awaitingShipment, int scheduledOrders, int openBackorders, int lateBackorders,
                       int ordersRejected7d, int unitsRejected7d, BigDecimal onTimeShare7d,
                       int repromisedToday, int etaChangesToday, int lateContainers, int overdueContainers, int openContainers,
                       BigDecimal p80HitRate28d, int predictionsScored28d, BigDecimal meanAbsErrorDays28d,
                       Integer minutesSinceLastMilestone, Integer minutesSinceLastOrder, int outboxPending, Integer outboxOldestSeconds) {}

    public record Day(LocalDate day, int orders, int unitsOrdered, int unitsShipped, int unitsReceived, int backordersCreated,
                      int rejected) {}

    public record Activity(String eventKey, OffsetDateTime at, String kind, String title, String detail) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public DashboardRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Kpis kpis() {
        ZoneId zone = clock.getZone();
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Object> p = new HashMap<>();
        p.put("now", now.toOffsetDateTime());
        p.put("today", today);
        p.put("todayStart", today.atStartOfDay(zone).toOffsetDateTime());
        p.put("yesterdayStart", today.minusDays(1).atStartOfDay(zone).toOffsetDateTime());
        p.put("yesterdayNow", now.minusDays(1).toOffsetDateTime());
        p.put("weekStart", monday.atStartOfDay(zone).toOffsetDateTime());
        p.put("lastWeekStart", monday.minusWeeks(1).atStartOfDay(zone).toOffsetDateTime());
        p.put("lastWeekNow", now.minusWeeks(1).toOffsetDateTime());
        p.put("weekAgo", now.minusDays(7).toOffsetDateTime());
        p.put("accuracyFrom", now.minusDays(28).toOffsetDateTime());
        return jdbc.sql("""
                with feed as (select * from customer_order where origin = 'FEED'),
                gate_in as (
                    select m.shipment_id, m.occurred_at
                    from shipment_milestone m
                    where m.type = 'RECEIVED_FC' and m.occurred_at >= :accuracyFrom and m.occurred_at < :now
                      and not exists (select 1 from shipment_milestone r where r.shipment_id = m.shipment_id and r.source = 'CARRIER_EDI_RECOVERY')
                ),
                scored as (
                    -- the prediction that was in force two weeks before the container reached the dock
                    select g.shipment_id, (g.occurred_at at time zone 'America/Vancouver')::date as actual,
                           (select l.predicted_arrival from eta_prediction_log l
                             where l.shipment_id = g.shipment_id and l.computed_at <= g.occurred_at - interval '14 days'
                             order by l.computed_at desc limit 1) as predicted
                    from gate_in g
                )
                select cast(:now as timestamptz) as as_of,
                  (select min(created_at) from feed) as history_since,
                  (select count(*) from feed where created_at >= :todayStart and created_at < :now) as orders_today,
                  (select count(*) from feed where created_at >= :yesterdayStart and created_at < :yesterdayNow) as orders_yesterday_same_time,
                  (select coalesce(sum(qty), 0) from feed where created_at >= :todayStart and created_at < :now) as units_ordered_today,
                  (select coalesce(sum(qty), 0) from feed where created_at >= :yesterdayStart and created_at < :yesterdayNow) as units_ordered_yesterday_same_time,
                  (select count(*) from feed where created_at >= :weekStart and created_at < :now) as orders_wtd,
                  (select count(*) from feed where created_at >= :lastWeekStart and created_at < :lastWeekNow) as orders_last_wtd,
                  (select coalesce(sum(qty), 0) from feed where shipped_at >= :todayStart and shipped_at < :now) as units_shipped_today,
                  (select coalesce(sum(qty), 0) from feed where shipped_at >= :yesterdayStart and shipped_at < :yesterdayNow) as units_shipped_yesterday_same_time,
                  (select coalesce(sum(qty), 0) from feed where shipped_at >= :weekStart and shipped_at < :now) as units_shipped_wtd,
                  (select coalesce(sum(qty), 0) from feed where shipped_at >= :lastWeekStart and shipped_at < :lastWeekNow) as units_shipped_last_wtd,
                  (select count(*) from shipment_milestone where type = 'RECEIVED_FC' and occurred_at >= :weekStart and occurred_at < :now) as containers_gated_in_wtd,
                  (select count(*) from shipment_milestone where type = 'RECEIVED_FC' and occurred_at >= :lastWeekStart and occurred_at < :lastWeekNow) as containers_gated_in_last_wtd,
                  (select coalesce(sum(on_hand_delta), 0) from inventory_movement where kind = 'RECEIPT' and occurred_at >= :weekStart and occurred_at < :now) as units_received_wtd,
                  (select coalesce(sum(on_hand_delta), 0) from inventory_movement where kind = 'RECEIPT' and occurred_at >= :lastWeekStart and occurred_at < :lastWeekNow) as units_received_last_wtd,
                  (select count(*) from customer_order where status = 'RESERVED' and origin <> 'VISITOR') as awaiting_shipment,
                  (select count(*) from customer_order where status = 'SCHEDULED' and origin <> 'VISITOR') as scheduled_orders,
                  (select count(*) from customer_order where status = 'BACKORDERED' and origin <> 'VISITOR') as open_backorders,
                  (select count(*) from customer_order where status = 'BACKORDERED' and origin <> 'VISITOR' and promise_date < :today) as late_backorders,
                  (select count(*) from feed where status = 'REJECTED' and created_at >= :weekAgo) as orders_rejected7d,
                  (select coalesce(sum(qty), 0) from feed where status = 'REJECTED' and created_at >= :weekAgo) as units_rejected7d,
                  -- on time = the first promise met what the customer asked for: today (reserved from stock)
                  -- for an online order, the need-by date (scheduled) for B2B; backordered and rejected are misses
                  (select round(avg(case when placed_status in ('RESERVED', 'SCHEDULED') then 1.0 else 0.0 end), 3)
                     from feed where created_at >= :weekAgo) as on_time_share7d,
                  (select count(*) from ops_note where kind = 'PROMISE' and created_at >= :todayStart) as repromised_today,
                  (select count(*) from eta_prediction_log where computed_at >= :todayStart and reason in ('MILESTONE', 'DAILY')) as eta_changes_today,
                  (select count(*) from shipment s join purchase_order po on po.id = s.po_id
                     where po.status = 'OPEN' and s.predicted_arrival > s.planned_arrival) as late_containers,
                  (select count(*) from shipment s join purchase_order po on po.id = s.po_id
                     where po.status = 'OPEN' and s.prediction_basis like 'Overdue%') as overdue_containers,
                  (select count(*) from purchase_order where status = 'OPEN') as open_containers,
                  (select round(avg(case when actual <= predicted then 1.0 else 0.0 end), 3) from scored where predicted is not null) as p80_hit_rate28d,
                  (select count(*) from scored where predicted is not null) as predictions_scored28d,
                  (select round(avg(abs(actual - predicted)), 1) from scored where predicted is not null) as mean_abs_error_days28d,
                  (select (extract(epoch from cast(:now as timestamptz) - max(recorded_at)) / 60)::int from shipment_milestone) as minutes_since_last_milestone,
                  (select (extract(epoch from cast(:now as timestamptz) - max(created_at)) / 60)::int from feed) as minutes_since_last_order,
                  (select count(*) from outbox_event where published_at is null) as outbox_pending,
                  (select (extract(epoch from cast(:now as timestamptz) - min(created_at)))::int from outbox_event where published_at is null) as outbox_oldest_seconds
                """)
                .params(p)
                .query(Kpis.class)
                .single();
    }

    /** One row per business day, oldest first, zeros where nothing happened. */
    public List<Day> daily(int days) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(days - 1L);
        var from = first.atStartOfDay(zone).toOffsetDateTime();
        Map<LocalDate, int[]> acc = new HashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) acc.put(d, new int[6]);
        record Bucket(LocalDate day, int col, int value) {}
        jdbc.sql("""
                select (created_at at time zone 'America/Vancouver')::date as day, 0 as col, count(*)::int as value
                  from customer_order where origin = 'FEED' and created_at >= :from group by 1
                union all
                select (created_at at time zone 'America/Vancouver')::date, 1, sum(qty)::int
                  from customer_order where origin = 'FEED' and created_at >= :from group by 1
                union all
                select (shipped_at at time zone 'America/Vancouver')::date, 2, sum(qty)::int
                  from customer_order where origin = 'FEED' and shipped_at >= :from group by 1
                union all
                select (occurred_at at time zone 'America/Vancouver')::date, 3, sum(on_hand_delta)::int
                  from inventory_movement where kind = 'RECEIPT' and occurred_at >= :from group by 1
                union all
                -- created as a backorder: promised later than the customer asked (B2B scheduled for its date is not)
                select (created_at at time zone 'America/Vancouver')::date, 4, count(*)::int
                  from customer_order where origin = 'FEED' and created_at >= :from
                   and placed_status = 'BACKORDERED' group by 1
                union all
                select (created_at at time zone 'America/Vancouver')::date, 5, count(*)::int
                  from customer_order where origin = 'FEED' and status = 'REJECTED' and created_at >= :from group by 1
                """)
                .param("from", from)
                .query(Bucket.class)
                .list()
                .forEach(b -> { int[] row = acc.get(b.day()); if (row != null) row[b.col()] = b.value(); });
        List<Day> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            int[] r = acc.get(d);
            out.add(new Day(d, r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        return out;
    }

    /**
     * What just happened, newest first. Only codes, enums and numbers are shown - never text a caller
     * supplied. Each row has a stable key (kind + id) and describes the event as it happened: an order
     * row shows the decision taken when it was placed, not the status it has reached since.
     */
    public List<Activity> activity(int limit) {
        return jdbc.sql("""
                (select 'ms:' || m.id as event_key, m.recorded_at as at, 'MILESTONE' as kind,
                        po.po_number || ' ' || replace(m.type, '_', ' ') as title,
                        po.origin_port || ' → ' || fc.code || ' · ' || m.source as detail
                   from shipment_milestone m join shipment s on s.id = m.shipment_id join purchase_order po on po.id = s.po_id
                   join fulfillment_center fc on fc.id = po.dest_fc_id
                   order by m.recorded_at desc limit :limit)
                union all
                (select 'order:' || o.id, o.created_at, 'ORDER', o.order_ref || ' ' || o.placed_status,
                        o.qty || ' × ' || sku.code || ' @ ' || fc.code ||
                        case when o.placed_status in ('BACKORDERED', 'SCHEDULED') then ' · promised ' || to_char(o.first_promise_date, 'Mon DD')
                             when o.placed_status = 'REJECTED' then ' · no date within the horizon' else '' end ||
                        case when o.origin = 'VISITOR' then ' · visitor' else '' end
                   from customer_order o join sku on sku.id = o.sku_id join fulfillment_center fc on fc.id = o.fc_id
                   where o.origin in ('FEED', 'VISITOR')
                   order by o.created_at desc limit :limit)
                union all
                (select 'ship:' || o.id, o.shipped_at, 'SHIPPED', o.order_ref || ' shipped', o.qty || ' × ' || sku.code || ' from ' || fc.code
                   from customer_order o join sku on sku.id = o.sku_id join fulfillment_center fc on fc.id = o.fc_id
                   where o.shipped_at is not null order by o.shipped_at desc limit :limit)
                union all
                (select 'grn:' || g.id, g.recorded_at, 'RECEIPT', po.po_number || ' put away',
                        g.units_received || ' units into ' || fc.code || case when g.units_damaged > 0 then ' · ' || g.units_damaged || ' damaged' else '' end
                   from goods_receipt g join shipment s on s.id = g.shipment_id join purchase_order po on po.id = s.po_id
                   join fulfillment_center fc on fc.id = po.dest_fc_id
                   order by g.recorded_at desc limit :limit)
                union all
                (select 'eta:' || l.id, l.computed_at, 'ETA', po.po_number || ' now due ' || to_char(l.predicted_arrival, 'Mon DD'),
                        l.confidence || ' confidence · ' || lower(l.reason) || ' · from ' || replace(l.stage, '_', ' ')
                   from eta_prediction_log l join shipment s on s.id = l.shipment_id join purchase_order po on po.id = s.po_id
                   where l.reason in ('MILESTONE', 'DAILY', 'STATS_REFRESH')
                   order by l.computed_at desc limit :limit)
                union all
                (select 'po:' || po.id, po.created_at, 'PO', po.po_number || ' placed with ' || po.supplier, po.origin_port || ' → ' || fc.code
                   from purchase_order po join fulfillment_center fc on fc.id = po.dest_fc_id
                   where po.client_ref is not null order by po.created_at desc limit :limit)
                union all
                (select 'note:' || n.id, n.created_at, n.kind, n.message, '' from ops_note n order by n.created_at desc limit :limit)
                order by at desc
                limit :limit
                """)
                .param("limit", limit)
                .query(Activity.class)
                .list();
    }

    public void addNote(String ref, String kind, String message) {
        jdbc.sql("insert into ops_note (ref, kind, message) values (:ref, :kind, :message) on conflict (ref) do nothing")
                .param("ref", ref).param("kind", kind).param("message", message).update();
    }
}
