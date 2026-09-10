package com.haoyu.inbound.procurement;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PurchaseOrderRepository {

    private static final String SUPPLY_SELECT = """
            select po.id            as po_id,
                   po.po_number,
                   l.id             as po_line_id,
                   l.sku_id,
                   po.dest_fc_id,
                   l.qty_ordered,
                   l.qty_received,
                   coalesce(s.planned_arrival, po.planned_arrival) as planned_arrival,
                   s.predicted_arrival,
                   s.predicted_confidence,
                   coalesce(s.current_stage, 'BOOKED') as current_stage,
                   s.id             as shipment_id
            from purchase_order_line l
            join purchase_order po on po.id = l.po_id
            left join shipment s on s.po_id = po.id
            where po.status = 'OPEN'
              and l.qty_received < l.qty_ordered
            """;

    private final JdbcClient jdbc;

    public PurchaseOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Outstanding inbound supply for one SKU at one FC, soonest arrival first. */
    public List<InboundSupply> openSupply(long skuId, long fcId) {
        return jdbc.sql(SUPPLY_SELECT + """
                  and l.sku_id = :sku and po.dest_fc_id = :fc
                order by coalesce(s.predicted_arrival, s.planned_arrival, po.planned_arrival), po.id
                """)
                .param("sku", skuId)
                .param("fc", fcId)
                .query(this::mapSupply)
                .list();
    }

    /** Outstanding inbound supply for one SKU across all FCs. */
    public List<InboundSupply> openSupply(long skuId) {
        return jdbc.sql(SUPPLY_SELECT + """
                  and l.sku_id = :sku
                order by coalesce(s.predicted_arrival, s.planned_arrival, po.planned_arrival), po.id
                """)
                .param("sku", skuId)
                .query(this::mapSupply)
                .list();
    }

    private InboundSupply mapSupply(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Date predicted = rs.getDate("predicted_arrival");
        long shipmentId = rs.getLong("shipment_id");
        return new InboundSupply(
                rs.getLong("po_id"),
                rs.getString("po_number"),
                rs.getLong("po_line_id"),
                rs.getLong("sku_id"),
                rs.getLong("dest_fc_id"),
                rs.getInt("qty_ordered"),
                rs.getInt("qty_received"),
                rs.getDate("planned_arrival").toLocalDate(),
                predicted == null ? null : predicted.toLocalDate(),
                rs.getString("predicted_confidence"),
                MilestoneType.valueOf(rs.getString("current_stage")),
                rs.wasNull() ? null : shipmentId);
    }
}
