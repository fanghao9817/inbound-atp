package com.haoyu.inbound.eta;

import com.haoyu.inbound.atp.AtpService;
import com.haoyu.inbound.catalog.CatalogRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a planner should look at first.
 * <ul>
 *   <li>Containers: OVERDUE (past the lane's P80 and still not at the dock) or LATE_VS_PLAN (predicted
 *       after the carrier's plan), with the commitments due before the new arrival.
 *   <li>Shortages: SKU x FC positions whose projected stock goes below zero inside the horizon - the
 *       promises that will slip unless something changes (an expedite, a transfer, a re-promise).
 * </ul>
 */
@RestController
class ExceptionsController {

    public record LateShipment(long shipmentId, String poNumber, String originPort, String destFc, String currentStage,
                               LocalDate plannedArrival, LocalDate predictedArrival, int daysLate, String confidence,
                               String basis, String reason, int commitmentsDueBeforeArrival) {}

    public record Shortage(String sku, String fc, LocalDate firstShortDate, int unitsShort, int availableNow,
                           int inboundUnits, int committedUnits) {}

    private final JdbcClient jdbc;
    private final AtpService atp;
    private final CatalogRepository catalog;
    private final Clock clock;

    ExceptionsController(JdbcClient jdbc, AtpService atp, CatalogRepository catalog, Clock clock) {
        this.jdbc = jdbc;
        this.atp = atp;
        this.catalog = catalog;
        this.clock = clock;
    }

    @GetMapping("/api/exceptions")
    List<LateShipment> lateShipments() {
        return jdbc.sql("""
                select s.id as shipment_id, po.po_number, po.origin_port, fc.code as dest_fc, s.current_stage,
                       s.planned_arrival, s.predicted_arrival,
                       (s.predicted_arrival - s.planned_arrival) as days_late,
                       s.predicted_confidence as confidence, s.prediction_basis as basis,
                       case when s.prediction_basis like 'Overdue%' then 'OVERDUE' else 'LATE_VS_PLAN' end as reason,
                       (select count(*) from demand_commitment dc
                          join purchase_order_line l on l.po_id = po.id and l.sku_id = dc.sku_id
                         where dc.fc_id = po.dest_fc_id
                           and dc.need_by < s.predicted_arrival + fc.receiving_buffer_days
                                         -- dock-to-stock in working days: one more day if a Sunday falls in between (buffers up to 6)
                                         + case when extract(isodow from s.predicted_arrival)::int % 7 + fc.receiving_buffer_days >= 7 then 1 else 0 end) as commitments_due_before_arrival
                from shipment s
                join purchase_order po on po.id = s.po_id
                join fulfillment_center fc on fc.id = po.dest_fc_id
                where po.status = 'OPEN'
                  and (s.predicted_arrival > s.planned_arrival or s.prediction_basis like 'Overdue%')
                order by (s.prediction_basis like 'Overdue%') desc, days_late desc, s.predicted_arrival
                """)
                .query(LateShipment.class)
                .list();
    }

    @GetMapping("/api/exceptions/shortages")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Shortage> shortages() {
        LocalDate today = LocalDate.now(clock);
        List<Shortage> out = new ArrayList<>();
        for (var sku : catalog.listSkus()) {
            for (var fc : catalog.listFcs()) {
                var inputs = atp.inputs(sku.id(), fc, today, null);
                var timeline = atp.timeline(inputs, fc, today).timeline();
                timeline.stream().filter(p -> p.projected() < 0).findFirst().ifPresent(first -> {
                    int worst = timeline.stream().mapToInt(com.haoyu.inbound.atp.AtpCalculator.Point::projected).min().orElse(0);
                    out.add(new Shortage(sku.code(), fc.code(), first.date(), -worst, inputs.availableNow(),
                            inputs.inbound().stream().mapToInt(s -> s.qtyOutstanding()).sum(),
                            inputs.commitments().stream().mapToInt(c -> c.qty()).sum()));
                });
            }
        }
        out.sort(Comparator.comparing(Shortage::firstShortDate).thenComparing(Shortage::unitsShort, Comparator.reverseOrder()));
        return out;
    }
}
