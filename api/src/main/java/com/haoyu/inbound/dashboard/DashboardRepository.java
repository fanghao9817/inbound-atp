package com.haoyu.inbound.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Operational numbers for the "Today" page, straight from the system of record. Day and week
 * boundaries are Vancouver local time; "this week" is Monday to now and is compared with the same
 * elapsed span of last week, so a Tuesday morning is not compared with a whole week.
 */
@Repository
public class DashboardRepository {

    static final String TZ = "America/Vancouver";

    public record Kpis(OffsetDateTime asOf,
                       int ordersToday, int ordersYesterday, int unitsOrderedToday, int unitsOrderedYesterday,
                       int ordersWtd, int ordersLastWtd,
                       int unitsShippedToday, int unitsShippedYesterday, int unitsShippedWtd, int unitsShippedLastWtd,
                       int containersReceivedWtd, int containersReceivedLastWtd, int unitsReceivedWtd,
                       int openBackorders, int lateBackorders, int unitsRejected7d, int ordersRejected7d,
                       int lateContainers, int openContainers,
                       BigDecimal p80HitRate28d, int predictionsScored28d, BigDecimal meanAbsErrorDays28d) {}

    public record Day(LocalDate day, int orders, int unitsOrdered, int unitsShipped, int unitsReceived, int backordersCreated,
                      int rejected) {}

    public record Activity(OffsetDateTime at, String kind, String title, String detail) {}

    private final JdbcClient jdbc;

    public DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Kpis kpis() {
        return jdbc.sql("""
                with b as (
                    select now() as now_ts,
                           date_trunc('day', now() at time zone :tz) at time zone :tz as today,
                           date_trunc('week', now() at time zone :tz) at time zone :tz as week_start
                ), w as (
                    select now_ts, today, today - interval '1 day' as yesterday, week_start,
                           week_start - interval '7 days' as last_week_start, now_ts - interval '7 days' as last_week_now
                    from b
                ), acc as (
                    -- at-sea promise: the last prediction made while the container was still DEPARTED_ORIGIN
                    select distinct on (s.id) s.id, l.predicted_arrival, (m.occurred_at at time zone :tz)::date as actual
                    from shipment s
                    join shipment_milestone m on m.shipment_id = s.id and m.type = 'RECEIVED_FC'
                    join eta_prediction_log l on l.shipment_id = s.id and l.stage = 'DEPARTED_ORIGIN'
                    where m.occurred_at >= now() - interval '28 days'
                    order by s.id, l.computed_at desc
                )
                select (select now_ts from w) as as_of,
                  (select count(*) from customer_order, w where created_at >= w.today) as orders_today,
                  (select count(*) from customer_order, w where created_at >= w.yesterday and created_at < w.today) as orders_yesterday,
                  (select coalesce(sum(qty), 0) from customer_order, w where created_at >= w.today) as units_ordered_today,
                  (select coalesce(sum(qty), 0) from customer_order, w where created_at >= w.yesterday and created_at < w.today) as units_ordered_yesterday,
                  (select count(*) from customer_order, w where created_at >= w.week_start) as orders_wtd,
                  (select count(*) from customer_order, w where created_at >= w.last_week_start and created_at < w.last_week_now) as orders_last_wtd,
                  (select coalesce(sum(qty), 0) from customer_order, w where shipped_at >= w.today) as units_shipped_today,
                  (select coalesce(sum(qty), 0) from customer_order, w where shipped_at >= w.yesterday and shipped_at < w.today) as units_shipped_yesterday,
                  (select coalesce(sum(qty), 0) from customer_order, w where shipped_at >= w.week_start) as units_shipped_wtd,
                  (select coalesce(sum(qty), 0) from customer_order, w where shipped_at >= w.last_week_start and shipped_at < w.last_week_now) as units_shipped_last_wtd,
                  (select count(*) from shipment_milestone, w where type = 'RECEIVED_FC' and occurred_at >= w.week_start) as containers_received_wtd,
                  (select count(*) from shipment_milestone, w where type = 'RECEIVED_FC' and occurred_at >= w.last_week_start and occurred_at < w.last_week_now) as containers_received_last_wtd,
                  (select coalesce(sum(on_hand_delta), 0) from inventory_movement, w where kind = 'RECEIPT' and occurred_at >= w.week_start) as units_received_wtd,
                  (select count(*) from customer_order where status = 'BACKORDERED') as open_backorders,
                  (select count(*) from customer_order, w where status = 'BACKORDERED' and promise_date < (w.today at time zone :tz)::date) as late_backorders,
                  (select coalesce(sum(qty), 0) from customer_order where status = 'REJECTED' and created_at >= now() - interval '7 days') as units_rejected7d,
                  (select count(*) from customer_order where status = 'REJECTED' and created_at >= now() - interval '7 days') as orders_rejected7d,
                  (select count(*) from shipment s join purchase_order po on po.id = s.po_id
                     where po.status = 'OPEN' and s.predicted_arrival > s.planned_arrival) as late_containers,
                  (select count(*) from purchase_order where status = 'OPEN') as open_containers,
                  (select round(avg(case when actual <= predicted_arrival then 1.0 else 0.0 end), 3) from acc) as p80_hit_rate28d,
                  (select count(*) from acc) as predictions_scored28d,
                  (select round(avg(abs(actual - predicted_arrival)), 1) from acc) as mean_abs_error_days28d
                """)
                .param("tz", TZ)
                .query(Kpis.class)
                .single();
    }

    public List<Day> daily(int days) {
        return jdbc.sql("""
                with d as (
                    select generate_series((now() at time zone :tz)::date - (:days - 1), (now() at time zone :tz)::date, interval '1 day')::date as day
                )
                select d.day,
                  (select count(*) from customer_order o where (o.created_at at time zone :tz)::date = d.day) as orders,
                  (select coalesce(sum(qty), 0) from customer_order o where (o.created_at at time zone :tz)::date = d.day) as units_ordered,
                  (select coalesce(sum(qty), 0) from customer_order o where (o.shipped_at at time zone :tz)::date = d.day) as units_shipped,
                  (select coalesce(sum(on_hand_delta), 0) from inventory_movement m
                     where m.kind = 'RECEIPT' and (m.occurred_at at time zone :tz)::date = d.day) as units_received,
                  -- created as a backorder = promised for a later day than the day it was placed
                  (select count(*) from customer_order o where (o.created_at at time zone :tz)::date = d.day
                     and o.promise_date > (o.created_at at time zone :tz)::date) as backorders_created,
                  (select count(*) from customer_order o where o.status = 'REJECTED' and (o.created_at at time zone :tz)::date = d.day) as rejected
                from d
                order by d.day
                """)
                .param("tz", TZ).param("days", days)
                .query(Day.class)
                .list();
    }

    /** What just happened, newest first: milestones, orders, receipts, prediction changes, new POs and ops notes. */
    public List<Activity> activity(int limit) {
        return jdbc.sql("""
                (select m.recorded_at as at, 'MILESTONE' as kind, po.po_number || ' ' || replace(m.type, '_', ' ') as title,
                        po.origin_port || ' → ' || fc.code || ' · ' || m.source as detail
                   from shipment_milestone m join shipment s on s.id = m.shipment_id join purchase_order po on po.id = s.po_id
                   join fulfillment_center fc on fc.id = po.dest_fc_id
                   order by m.recorded_at desc limit :limit)
                union all
                (select o.created_at, 'ORDER', o.order_ref || ' ' || o.status,
                        o.qty || ' × ' || sku.code || ' @ ' || fc.code ||
                        case when o.status = 'BACKORDERED' then ' · promised ' || to_char(o.promise_date, 'Mon DD')
                             when o.status = 'REJECTED' then ' · no date within horizon' else '' end
                   from customer_order o join sku on sku.id = o.sku_id join fulfillment_center fc on fc.id = o.fc_id
                   order by o.created_at desc limit :limit)
                union all
                (select o.shipped_at, 'SHIPPED', o.order_ref || ' shipped', o.qty || ' × ' || sku.code || ' from ' || fc.code
                   from customer_order o join sku on sku.id = o.sku_id join fulfillment_center fc on fc.id = o.fc_id
                   where o.shipped_at is not null order by o.shipped_at desc limit :limit)
                union all
                (select m.occurred_at, 'RECEIPT', 'Received ' || m.on_hand_delta || ' × ' || sku.code, 'into ' || fc.code
                   from inventory_movement m join sku on sku.id = m.sku_id join fulfillment_center fc on fc.id = m.fc_id
                   where m.kind = 'RECEIPT' order by m.occurred_at desc limit :limit)
                union all
                (select l.computed_at, 'ETA', po.po_number || ' now due ' || to_char(l.predicted_arrival, 'Mon DD'),
                        l.confidence || ' confidence · from ' || replace(l.stage, '_', ' ')
                   from eta_prediction_log l join shipment s on s.id = l.shipment_id join purchase_order po on po.id = s.po_id
                   order by l.computed_at desc limit :limit)
                union all
                (select po.created_at, 'PO', po.po_number || ' placed with ' || po.supplier, po.origin_port || ' → ' || fc.code
                   from purchase_order po join fulfillment_center fc on fc.id = po.dest_fc_id
                   where po.client_ref is not null order by po.created_at desc limit :limit)
                union all
                (select n.created_at, n.kind, n.message, '' from ops_note n order by n.created_at desc limit :limit)
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
