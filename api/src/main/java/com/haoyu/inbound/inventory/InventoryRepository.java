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

    public List<InventoryPosition> listPositions(long skuId) {
        return jdbc.sql("select sku_id, fc_id, on_hand, reserved from inventory_position where sku_id = :sku order by fc_id")
                .param("sku", skuId)
                .query(InventoryPosition.class)
                .list();
    }

    /** Commitments not yet fulfilled; past-due ones are still demand, so no lower bound on need_by. */
    public List<DemandCommitment> listOpenCommitments(long skuId, long fcId, LocalDate horizon) {
        return jdbc.sql("""
                select id, sku_id, fc_id, qty, need_by, reference
                from demand_commitment
                where sku_id = :sku and fc_id = :fc and need_by <= :horizon
                order by need_by, id
                """)
                .param("sku", skuId)
                .param("fc", fcId)
                .param("horizon", horizon)
                .query(DemandCommitment.class)
                .list();
    }
}
