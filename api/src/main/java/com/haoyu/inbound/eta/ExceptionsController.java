package com.haoyu.inbound.eta;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Planner view of what needs attention: containers now predicted to land after their plan. */
@RestController
class ExceptionsController {

    public record LateShipment(long shipmentId, String poNumber, String originPort, String destFc, String currentStage,
                               LocalDate plannedArrival, LocalDate predictedArrival, int daysLate, String confidence,
                               String basis, int commitmentsDueBeforeArrival) {}

    private final JdbcClient jdbc;

    ExceptionsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/exceptions")
    List<LateShipment> lateShipments() {
        return jdbc.sql("""
                select s.id as shipment_id, po.po_number, po.origin_port, fc.code as dest_fc, s.current_stage,
                       s.planned_arrival, s.predicted_arrival,
                       (s.predicted_arrival - s.planned_arrival) as days_late,
                       s.predicted_confidence as confidence, s.prediction_basis as basis,
                       (select count(*)
                          from demand_commitment dc
                          join purchase_order_line l on l.po_id = po.id and l.sku_id = dc.sku_id
                         where dc.fc_id = po.dest_fc_id
                           and dc.need_by < s.predicted_arrival + fc.receiving_buffer_days) as commitments_due_before_arrival
                from shipment s
                join purchase_order po on po.id = s.po_id
                join fulfillment_center fc on fc.id = po.dest_fc_id
                where po.status = 'OPEN'
                  and s.predicted_arrival > s.planned_arrival
                order by days_late desc, s.predicted_arrival
                """)
                .query(LateShipment.class)
                .list();
    }
}
