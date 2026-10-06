package com.haoyu.inbound.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String ORDER_SELECT = """
            select id, order_ref, channel, sku_id, fc_id, qty, status, promise_date, need_by,
                   created_at, reserved_at, shipped_at, cancelled_at
            from customer_order
            """;

    private static final String VIEW_SELECT = """
            select o.id, o.order_ref, o.channel, o.origin, sku.code as sku, fc.code as fc, o.qty, o.status, o.promise_date,
                   o.first_promise_date, o.need_by, o.created_at, o.reserved_at, o.shipped_at, o.cancelled_at
            from customer_order o
            join sku on sku.id = o.sku_id
            join fulfillment_center fc on fc.id = o.fc_id
            """;

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String orderRef, Channel channel, Origin origin, long skuId, long fcId, int qty, OrderStatus status,
                       LocalDate promiseDate, LocalDate needBy, boolean reservedNow) {
        return jdbc.sql("""
                insert into customer_order (order_ref, channel, origin, sku_id, fc_id, qty, status, promise_date, first_promise_date, need_by, reserved_at)
                values (:ref, :channel, :origin, :sku, :fc, :qty, :status, :promise, :promise, :needBy, case when :reservedNow then now() end)
                returning id
                """)
                .param("ref", orderRef).param("channel", channel.name()).param("origin", origin.name()).param("sku", skuId).param("fc", fcId)
                .param("qty", qty).param("status", status.name())
                .param("promise", promiseDate, Types.DATE).param("needBy", needBy, Types.DATE)
                .param("reservedNow", reservedNow)
                .query(Long.class)
                .single();
    }

    public Optional<CustomerOrder> find(long id) {
        return jdbc.sql(ORDER_SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    /** Row lock on the order; callers lock the inventory position first (lock order: position, then order). */
    public Optional<CustomerOrder> lock(long id) {
        return jdbc.sql(ORDER_SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    public Optional<OrderView> findView(long id) {
        return jdbc.sql(VIEW_SELECT + " where o.id = :id").param("id", id).query(OrderView.class).optional();
    }

    public Optional<OrderView> findViewByRef(String ref) {
        return jdbc.sql(VIEW_SELECT + " where o.order_ref = :ref").param("ref", ref).query(OrderView.class).optional();
    }

    public List<OrderView> list(String status, String channel, int limit) {
        return jdbc.sql(VIEW_SELECT + """
                 where o.status = coalesce(:status, o.status) and o.channel = coalesce(:channel, o.channel)
                 order by o.created_at desc, o.id desc
                 limit :limit
                """)
                .param("status", status, Types.VARCHAR).param("channel", channel, Types.VARCHAR).param("limit", limit)
                .query(OrderView.class)
                .list();
    }

    /**
     * Open commitments (SCHEDULED and BACKORDERED) at one position, earliest due date first: the order
     * in which arriving stock is handed out.
     */
    public List<CustomerOrder> commitments(long skuId, long fcId) {
        return jdbc.sql(ORDER_SELECT + """
                 where sku_id = :sku and fc_id = :fc and status in ('SCHEDULED', 'BACKORDERED')
                 order by promise_date, created_at, id
                """)
                .param("sku", skuId).param("fc", fcId)
                .query(this::map)
                .list();
    }

    public record Position(long skuId, long fcId) {}

    public List<Position> positionsWithCommitments() {
        return jdbc.sql("select distinct sku_id, fc_id from customer_order where status in ('SCHEDULED', 'BACKORDERED') order by sku_id, fc_id")
                .query(Position.class).list();
    }

    public int markReservedFromCommitment(long id) {
        return jdbc.sql("""
                update customer_order set status = 'RESERVED', reserved_at = now(), version = version + 1
                where id = :id and status in ('SCHEDULED', 'BACKORDERED')
                """).param("id", id).update();
    }

    /** Moves the promise later (inbound stock slipped) and, if it is now later than wanted, marks it BACKORDERED. */
    public int repromise(long id, LocalDate newPromise) {
        return jdbc.sql("""
                update customer_order
                set promise_date = :promise, version = version + 1,
                    status = case when need_by is not null and :promise > need_by then 'BACKORDERED' else status end
                where id = :id and status in ('SCHEDULED', 'BACKORDERED') and promise_date < :promise
                """).param("id", id).param("promise", newPromise).update();
    }

    public void markShipped(long id) {
        jdbc.sql("update customer_order set status = 'SHIPPED', shipped_at = now(), version = version + 1 where id = :id")
                .param("id", id).update();
    }

    /** Visitor orders hold stock like a shopping-cart hold: released if not completed within the hour. */
    public List<Long> expiredVisitorOrders() {
        return jdbc.sql("""
                select id from customer_order
                where origin = 'VISITOR' and status in ('RESERVED', 'SCHEDULED', 'BACKORDERED')
                  and created_at < now() - interval '1 hour'
                order by id
                """).query(Long.class).list();
    }

    public int visitorOrdersSince(java.time.OffsetDateTime since) {
        return jdbc.sql("select count(*) from customer_order where origin = 'VISITOR' and created_at >= :since")
                .param("since", since).query(Integer.class).single();
    }

    public void markCancelled(long id) {
        jdbc.sql("update customer_order set status = 'CANCELLED', cancelled_at = now(), version = version + 1 where id = :id")
                .param("id", id).update();
    }

    private CustomerOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerOrder(
                rs.getLong("id"), rs.getString("order_ref"), Channel.valueOf(rs.getString("channel")),
                rs.getLong("sku_id"), rs.getLong("fc_id"), rs.getInt("qty"), OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("promise_date", LocalDate.class), rs.getObject("need_by", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("reserved_at", OffsetDateTime.class),
                rs.getObject("shipped_at", OffsetDateTime.class), rs.getObject("cancelled_at", OffsetDateTime.class));
    }
}
