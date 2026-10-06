package com.haoyu.inbound.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes stock. Every change is one conditional UPDATE that keeps the position
 * valid (on_hand >= 0, reserved >= 0, reserved <= on_hand) plus one ledger row, in the caller's
 * transaction. If the condition fails nothing changes and {@link InsufficientStockException} is
 * thrown - there is no read-then-write window in which two callers could both take the last unit.
 */
@Service
public class InventoryService {

    public enum Kind { RECEIPT, RESERVE, RELEASE, SHIP, ADJUST }

    /** What caused a movement, e.g. ("ORDER", 42) or ("SHIPMENT", 7). */
    public record Ref(String type, long id) {}

    public static class InsufficientStockException extends IllegalStateException {
        InsufficientStockException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;

    public InventoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the SKU x FC row for the rest of the transaction - the serialization point for every
     * decision about that position (placing an order, allocating backorders, posting a receipt).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryPosition lock(long skuId, long fcId) {
        jdbc.sql("insert into inventory_position (sku_id, fc_id) values (:sku, :fc) on conflict do nothing")
                .param("sku", skuId).param("fc", fcId).update();
        return jdbc.sql("select sku_id, fc_id, on_hand, reserved from inventory_position where sku_id = :sku and fc_id = :fc for update")
                .param("sku", skuId).param("fc", fcId)
                .query(InventoryPosition.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(long skuId, long fcId, int qty, Ref ref) {
        apply(skuId, fcId, 0, qty, Kind.RESERVE, ref);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long skuId, long fcId, int qty, Ref ref) {
        apply(skuId, fcId, 0, -qty, Kind.RELEASE, ref);
    }

    /** Picking and shipping consumes the reservation and the physical stock together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ship(long skuId, long fcId, int qty, Ref ref) {
        apply(skuId, fcId, -qty, -qty, Kind.SHIP, ref);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void receive(long skuId, long fcId, int qty, Ref ref) {
        jdbc.sql("insert into inventory_position (sku_id, fc_id) values (:sku, :fc) on conflict do nothing")
                .param("sku", skuId).param("fc", fcId).update();
        apply(skuId, fcId, qty, 0, Kind.RECEIPT, ref);
    }

    private void apply(long skuId, long fcId, int onHandDelta, int reservedDelta, Kind kind, Ref ref) {
        if (onHandDelta == 0 && reservedDelta == 0) return;
        int rows = jdbc.sql("""
                update inventory_position
                set on_hand = on_hand + :oh, reserved = reserved + :rs, version = version + 1, updated_at = now()
                where sku_id = :sku and fc_id = :fc
                  and on_hand + :oh >= 0
                  and reserved + :rs >= 0
                  and reserved + :rs <= on_hand + :oh
                """)
                .param("oh", onHandDelta).param("rs", reservedDelta)
                .param("sku", skuId).param("fc", fcId)
                .update();
        if (rows != 1) {
            throw new InsufficientStockException("%s of on_hand %+d / reserved %+d would make sku %d at fc %d invalid"
                    .formatted(kind, onHandDelta, reservedDelta, skuId, fcId));
        }
        jdbc.sql("""
                insert into inventory_movement (sku_id, fc_id, kind, on_hand_delta, reserved_delta, ref_type, ref_id)
                values (:sku, :fc, :kind, :oh, :rs, :refType, :refId)
                """)
                .param("sku", skuId).param("fc", fcId).param("kind", kind.name())
                .param("oh", onHandDelta).param("rs", reservedDelta)
                .param("refType", ref.type()).param("refId", ref.id())
                .update();
    }
}
