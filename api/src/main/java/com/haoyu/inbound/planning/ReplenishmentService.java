package com.haoyu.inbound.planning;

import com.haoyu.inbound.eta.LaneStatsRepository;
import com.haoyu.inbound.procurement.MilestoneType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly replenishment, periodic review with an order-up-to level, per SKU x FC:
 * <pre>
 *   f  weekly demand  = units ordered by the live feed in the last min(d, 28) days, per week;
 *                       while d &lt; 28 days of history exist it is blended with a prior (cold start)
 *   L  lead time      = lane P50 BOOKED -> RECEIVED_FC (dbt) + the FC's dock-to-stock days
 *   S  order-up-to    = f x (L in weeks + 1 review week + 2 safety weeks)
 *   IP position       = available now + open PO outstanding - open commitments (scheduled + backordered)
 *   order             = S - IP, rounded up to a case pack of 5; nothing below 10 units
 * </pre>
 * Lines are grouped into one proposed PO (one container) per origin port x FC, with an idempotent
 * clientRef REPL-{ISO week}-{origin}-{FC}, so the buyer can submit the same week's proposal twice safely.
 */
@Service
public class ReplenishmentService {

    static final int CASE_PACK = 5;
    static final int MIN_ORDER = 10;
    static final double REVIEW_WEEKS = 1;
    static final double SAFETY_WEEKS = 2;
    static final int DEFAULT_LEAD_DAYS = 42;
    static final int HISTORY_DAYS = 28;

    public record Line(String sku, String fc, String originPort, String supplier, double weeklyDemand, int leadTimeDays,
                       int orderUpTo, int availableNow, int inbound, int committed, int position, double weeksOfCover,
                       int suggestedQty) {}

    public record ProposedLine(String sku, int qty) {}

    public record Proposal(String clientRef, String supplier, String originPort, String destFc, List<ProposedLine> lines) {}

    public record Plan(LocalDate asOf, double daysOfHistory, double priorWeeklyUnits, List<Line> lines, List<Proposal> proposals) {}

    private record Row(String sku, String fc, String originPort, String supplier, int receivingBufferDays, int unitsInWindow,
                       int availableNow, int inbound, int committed) {}

    private final JdbcClient jdbc;
    private final LaneStatsRepository laneStats;
    private final Clock clock;
    private final double priorWeeklyUnits;

    public ReplenishmentService(JdbcClient jdbc, LaneStatsRepository laneStats, Clock clock,
                                @Value("${app.planning.prior-weekly-units:14}") double priorWeeklyUnits) {
        this.jdbc = jdbc;
        this.laneStats = laneStats;
        this.clock = clock;
        this.priorWeeklyUnits = priorWeeklyUnits;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan plan() {
        double d = jdbc.sql("select coalesce(extract(epoch from now() - min(created_at)) / 86400, 0) from customer_order where origin = 'FEED'")
                .query(Double.class).single();
        int window = (int) Math.max(1, Math.min(HISTORY_DAYS, Math.ceil(d)));
        List<Row> rows = jdbc.sql("""
                with demand as (
                    select sku_id, fc_id, sum(qty) as units
                    from customer_order
                    where origin = 'FEED' and status <> 'CANCELLED' and created_at >= now() - make_interval(days => :window)
                    group by sku_id, fc_id
                ), inbound as (
                    select l.sku_id, po.dest_fc_id as fc_id, sum(l.qty_ordered - l.qty_received) as units
                    from purchase_order_line l join purchase_order po on po.id = l.po_id
                    where po.status = 'OPEN'
                    group by l.sku_id, po.dest_fc_id
                ), committed as (
                    select sku_id, fc_id, sum(qty) as units from demand_commitment group by sku_id, fc_id
                )
                select sku.code as sku, fc.code as fc, src.origin_port, src.supplier, fc.receiving_buffer_days,
                       coalesce(d.units, 0) as units_in_window,
                       ip.on_hand - ip.reserved as available_now,
                       coalesce(i.units, 0) as inbound,
                       coalesce(c.units, 0) as committed
                from inventory_position ip
                join sku on sku.id = ip.sku_id
                join sku_source src on src.sku_id = ip.sku_id
                join fulfillment_center fc on fc.id = ip.fc_id
                left join demand d on d.sku_id = ip.sku_id and d.fc_id = ip.fc_id
                left join inbound i on i.sku_id = ip.sku_id and i.fc_id = ip.fc_id
                left join committed c on c.sku_id = ip.sku_id and c.fc_id = ip.fc_id
                order by sku.code, fc.code
                """)
                .param("window", window)
                .query(Row.class)
                .list();

        List<Line> lines = rows.stream().map(r -> {
            int lead = laneStats.find(r.originPort(), r.fc(), MilestoneType.BOOKED, MilestoneType.RECEIVED_FC)
                    .map(s -> (int) Math.ceil(s.p50Days().doubleValue()))
                    .orElse(DEFAULT_LEAD_DAYS - r.receivingBufferDays()) + r.receivingBufferDays();
            return evaluate(r.sku(), r.fc(), r.originPort(), r.supplier(), r.unitsInWindow(), Math.min(d, HISTORY_DAYS),
                    priorWeeklyUnits, lead, r.availableNow(), r.inbound(), r.committed());
        }).sorted(Comparator.comparingDouble(Line::weeksOfCover)).toList();

        LocalDate today = LocalDate.now(clock);
        String week = "%dW%02d".formatted(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        Map<String, Proposal> byContainer = new LinkedHashMap<>();
        lines.stream().filter(l -> l.suggestedQty() > 0)
                .sorted(Comparator.comparing(Line::originPort).thenComparing(Line::fc).thenComparing(Line::sku))
                .forEach(l -> byContainer.computeIfAbsent(l.originPort() + "|" + l.fc(), k ->
                                new Proposal("REPL-%s-%s-%s".formatted(week, l.originPort(), l.fc()), l.supplier(), l.originPort(), l.fc(), new ArrayList<>()))
                        .lines().add(new ProposedLine(l.sku(), l.suggestedQty())));
        return new Plan(today, Math.round(d * 10) / 10.0, priorWeeklyUnits, lines, List.copyOf(byContainer.values()));
    }

    /** Pure policy, unit-tested on its own. {@code daysObserved} is capped at 28 by the caller. */
    static Line evaluate(String sku, String fc, String origin, String supplier, int unitsInWindow, double daysObserved,
                         double prior, int leadDays, int availableNow, int inbound, int committed) {
        double observed = daysObserved <= 0 ? 0 : unitsInWindow * 7.0 / Math.max(daysObserved, 1);
        double weight = Math.min(daysObserved, HISTORY_DAYS) / HISTORY_DAYS;              // 0 at go-live, 1 after four weeks
        double f = weight * observed + (1 - weight) * prior;
        int s = (int) Math.ceil(f * (leadDays / 7.0 + REVIEW_WEEKS + SAFETY_WEEKS));
        int position = availableNow + inbound - committed;
        int need = s - position;
        int qty = need < MIN_ORDER ? 0 : (int) (Math.ceil(need / (double) CASE_PACK) * CASE_PACK);
        double cover = f <= 0 ? 999 : Math.round(position / f * 10) / 10.0;
        return new Line(sku, fc, origin, supplier, Math.round(f * 10) / 10.0, leadDays, s, availableNow, inbound, committed,
                position, cover, qty);
    }
}
