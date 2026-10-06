package com.haoyu.inbound.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventoryPosition> findPosition(long skuId, long fcId) {
        return jdbc.sql("select sku_id, fc_id, on_hand, reserved from inventory_position where sku_id = :sku and fc_id = :fc")
                .param("sku", skuId)
                .param("fc", fcId)
                .query(InventoryPosition.class)
                .optional();
    }

    /**
     * Open commitments (SCHEDULED and BACKORDERED orders, via the demand_commitment view); past-due ones
     * are still demand, so no lower bound on need_by.
     * {@code excludeOrderId} leaves one order's own commitment out - used when deciding whether that
     * backorder can now be served from stock.
     */
    public List<DemandCommitment> listOpenCommitments(long skuId, long fcId, LocalDate horizon, Long excludeOrderId) {
        return jdbc.sql("""
                select id, sku_id, fc_id, qty, need_by, reference, order_id
                from demand_commitment
                where sku_id = :sku and fc_id = :fc and need_by <= :horizon
                  and (cast(:exclude as bigint) is null or order_id is distinct from cast(:exclude as bigint))
                order by need_by, id
                """)
                .param("sku", skuId)
                .param("fc", fcId)
                .param("horizon", horizon)
                .param("exclude", excludeOrderId, java.sql.Types.BIGINT)
                .query(DemandCommitment.class)
                .list();
    }

    public List<DemandCommitment> listOpenCommitments(long skuId, long fcId, LocalDate horizon) {
        return listOpenCommitments(skuId, fcId, horizon, null);
    }

}
