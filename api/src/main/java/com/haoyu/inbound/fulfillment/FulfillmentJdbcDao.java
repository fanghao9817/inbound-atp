package com.haoyu.inbound.fulfillment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * Deliberately written against plain {@code java.sql} — no JdbcClient, no ORM — because this is the
 * read path a fulfillment engine lives or dies by, and it should be obvious exactly which
 * statements run and in which transaction.
 *
 * <p>Both reads happen on one connection inside one read-only, repeatable-read transaction, so the
 * inventory count and the purchase-order list come from the same snapshot; otherwise a receipt
 * landing between the two queries could be counted twice (once as inventory, once as inbound).
 * The SQL sticks to the ANSI subset so it runs unchanged on PostgreSQL and MySQL.
 */
@Repository
public class FulfillmentJdbcDao {

    private static final String INVENTORY_SQL = """
            select coalesce(sum(ip.on_hand - ip.reserved), 0) as available
            from inventory_position ip
            join sku on sku.id = ip.sku_id
            join fulfillment_center fc on fc.id = ip.fc_id
            where sku.code = ?
              and (? is null or fc.code = ?)
            """;

    private static final String INBOUND_SQL = """
            select po.id,
                   po.po_number,
                   l.qty_ordered - l.qty_received as qty_outstanding,
                   coalesce(s.predicted_arrival, s.planned_arrival, po.planned_arrival) as expected_at,
                   s.predicted_confidence
            from purchase_order_line l
            join purchase_order po on po.id = l.po_id
            join sku on sku.id = l.sku_id
            join fulfillment_center fc on fc.id = po.dest_fc_id
            left join shipment s on s.po_id = po.id
            where po.status = 'OPEN'
              and l.qty_received < l.qty_ordered
              and sku.code = ?
              and (? is null or fc.code = ?)
            order by expected_at asc, po.id asc
            """;

    public record Snapshot(int inventoryAvailable, List<InboundLine> inbound) {}

    private final DataSource dataSource;

    public FulfillmentJdbcDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Snapshot snapshot(String skuCode, String fcCode) {
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                int available = readAvailable(conn, skuCode, fcCode);
                List<InboundLine> inbound = readInbound(conn, skuCode, fcCode);
                conn.commit();
                return new Snapshot(available, inbound);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setReadOnly(false);
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new FulfillmentDataException("fulfillment snapshot failed for sku=" + skuCode, e);
        }
    }

    private static int readAvailable(Connection conn, String skuCode, String fcCode) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INVENTORY_SQL)) {
            stmt.setString(1, skuCode);
            setNullableString(stmt, 2, fcCode);
            setNullableString(stmt, 3, fcCode);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("available") : 0;   // aggregate: exactly one row
            }
        }
    }

    private static List<InboundLine> readInbound(Connection conn, String skuCode, String fcCode) throws SQLException {
        List<InboundLine> lines = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(INBOUND_SQL)) {
            stmt.setString(1, skuCode);
            setNullableString(stmt, 2, fcCode);
            setNullableString(stmt, 3, fcCode);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    lines.add(new InboundLine(
                            rs.getLong("id"),
                            rs.getString("po_number"),
                            rs.getInt("qty_outstanding"),
                            rs.getObject("expected_at", LocalDate.class),
                            rs.getString("predicted_confidence")));
                }
            }
        }
        return lines;
    }

    /** A typed NULL: PostgreSQL cannot infer the type of a bare {@code ? is null} parameter. */
    private static void setNullableString(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.VARCHAR);
        } else {
            stmt.setString(index, value);
        }
    }

    public static class FulfillmentDataException extends RuntimeException {
        FulfillmentDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
