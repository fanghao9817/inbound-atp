package com.haoyu.inbound.procurement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read model for the planner screens: purchase orders with their lines and shipment status. */
@Repository
public class PurchaseOrderQueryRepository {

    public record LineView(String skuCode, String skuName, int qtyOrdered, int qtyReceived) {}

    public record PurchaseOrderView(long id, String poNumber, String supplier, String originPort, String destFc,
                                    String status, LocalDate plannedArrival, Long shipmentId, String carrier,
                                    String containerNo, String currentStage, LocalDate predictedArrival,
                                    String predictedConfidence, String predictionBasis, Integer daysLate,
                                    List<LineView> lines) {}

    private final JdbcClient jdbc;

    public PurchaseOrderQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PurchaseOrderView> list(String status, int limit) {
        Map<Long, PurchaseOrderView> byId = new LinkedHashMap<>();
        jdbc.sql("""
                with po_page as (
                    select po.* from purchase_order po
                    where po.status = coalesce(:status, po.status)
                    order by po.planned_arrival, po.id
                    limit :limit
                )
                select po.id, po.po_number, po.supplier, po.origin_port, fc.code as dest_fc, po.status, po.planned_arrival,
                       s.id as shipment_id, s.carrier, s.container_no, s.current_stage, s.predicted_arrival,
                       s.predicted_confidence, s.prediction_basis,
                       case when s.predicted_arrival is not null then s.predicted_arrival - s.planned_arrival end as days_late,
                       sku.code as sku_code, sku.name as sku_name, l.qty_ordered, l.qty_received
                from po_page po
                join fulfillment_center fc on fc.id = po.dest_fc_id
                left join shipment s on s.po_id = po.id
                join purchase_order_line l on l.po_id = po.id
                join sku on sku.id = l.sku_id
                order by po.planned_arrival, po.id, sku.code
                """)
                .param("status", status, java.sql.Types.VARCHAR)
                .param("limit", limit)
                .query((ResultSet rs, int i) -> {
                    long id = rs.getLong("id");
                    PurchaseOrderView view = byId.get(id);
                    if (view == null) {
                        view = mapHeader(rs);
                        byId.put(id, view);
                    }
                    view.lines().add(new LineView(rs.getString("sku_code"), rs.getString("sku_name"),
                            rs.getInt("qty_ordered"), rs.getInt("qty_received")));
                    return view;
                })
                .list();
        return new ArrayList<>(byId.values());
    }

    private PurchaseOrderView mapHeader(ResultSet rs) throws SQLException {
        long shipmentId = rs.getLong("shipment_id");
        boolean noShipment = rs.wasNull();
        java.sql.Date predicted = rs.getDate("predicted_arrival");
        int daysLate = rs.getInt("days_late");
        boolean noDaysLate = rs.wasNull();
        return new PurchaseOrderView(
                rs.getLong("id"), rs.getString("po_number"), rs.getString("supplier"), rs.getString("origin_port"),
                rs.getString("dest_fc"), rs.getString("status"), rs.getDate("planned_arrival").toLocalDate(),
                noShipment ? null : shipmentId, rs.getString("carrier"), rs.getString("container_no"),
                rs.getString("current_stage"), predicted == null ? null : predicted.toLocalDate(),
                rs.getString("predicted_confidence"), rs.getString("prediction_basis"),
                noDaysLate ? null : daysLate, new ArrayList<>());
    }
}
