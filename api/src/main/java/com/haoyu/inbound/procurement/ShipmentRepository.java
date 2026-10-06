package com.haoyu.inbound.procurement;

import com.haoyu.inbound.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShipmentRepository {

    private static final String SHIPMENT_SELECT = """
            select id, po_id, carrier, container_no, planned_departure, planned_arrival, predicted_arrival,
                   predicted_confidence, prediction_basis, current_stage, version
            from shipment
            """;

    private final JdbcClient jdbc;

    public ShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Shipment> find(long id) {
        return jdbc.sql(SHIPMENT_SELECT + " where id = :id").param("id", id).query(this::mapShipment).optional();
    }

    public Shipment require(long id) {
        return find(id).orElseThrow(() -> new NotFoundException("shipment", id));
    }

    public List<ShipmentMilestone> milestones(long shipmentId) {
        return jdbc.sql("""
                select id, shipment_id, type, occurred_at, source, event_id, recorded_at
                from shipment_milestone
                where shipment_id = :id
                order by occurred_at, id
                """)
                .param("id", shipmentId)
                .query(this::mapMilestone)
                .list();
    }

    /**
     * Inserts the milestone unless this event id or this (shipment, stage) is already recorded; a single
     * "on conflict do nothing" without a target covers both unique constraints, so there is no
     * check-then-insert race. Returns false when nothing was inserted.
     */
    public boolean insertMilestoneIfNew(long shipmentId, MilestoneType type, OffsetDateTime occurredAt,
                                        String source, UUID eventId) {
        int rows = jdbc.sql("""
                insert into shipment_milestone (shipment_id, type, occurred_at, source, event_id)
                values (:shipment, :type, :occurredAt, :source, :eventId)
                on conflict do nothing
                """)
                .param("shipment", shipmentId)
                .param("type", type.name())
                .param("occurredAt", occurredAt)
                .param("source", source)
                .param("eventId", eventId)
                .update();
        return rows == 1;
    }

    /** The milestone that blocked an insert: same event id, or same stage of the same shipment. */
    public Optional<ShipmentMilestone> findMilestone(long shipmentId, MilestoneType type, UUID eventId) {
        return jdbc.sql("""
                select id, shipment_id, type, occurred_at, source, event_id, recorded_at
                from shipment_milestone
                where event_id = :eventId or (shipment_id = :shipment and type = :type)
                order by (event_id = :eventId) desc
                limit 1
                """)
                .param("eventId", eventId).param("shipment", shipmentId).param("type", type.name())
                .query(this::mapMilestone)
                .optional();
    }

    /** When the latest earlier stage happened; a later stage cannot have happened before it. */
    public Optional<OffsetDateTime> latestEarlierMilestone(long shipmentId, MilestoneType type) {
        return jdbc.sql("""
                select max(occurred_at) from shipment_milestone
                where shipment_id = :shipment and type = any(:earlier)
                """)
                .param("shipment", shipmentId)
                .param("earlier", java.util.Arrays.stream(MilestoneType.values()).filter(t -> type.isAfter(t)).map(Enum::name).toArray(String[]::new))
                .query(OffsetDateTime.class)
                .list().stream().filter(java.util.Objects::nonNull).findFirst();    // max() over no rows is one NULL row
    }

    public boolean isPurchaseOrderOpen(long shipmentId) {
        return jdbc.sql("select po.status = 'OPEN' from shipment s join purchase_order po on po.id = s.po_id where s.id = :id")
                .param("id", shipmentId).query(Boolean.class).single();
    }

    /** Moves the stage forward only; milestones can arrive out of order and must not regress it. */
    public void advanceStage(long shipmentId, MilestoneType stage) {
        jdbc.sql("""
                update shipment
                set current_stage = :stage, updated_at = now(), version = version + 1
                where id = :id and current_stage = any(:earlier)
                """)
                .param("id", shipmentId)
                .param("stage", stage.name())
                .param("earlier", java.util.Arrays.stream(MilestoneType.values()).filter(stage::isAfter).map(Enum::name).toArray(String[]::new))
                .update();
    }

    /** Optimistic-locking update; returns false if another writer changed the row first. */
    public boolean updatePrediction(long shipmentId, int expectedVersion, LocalDate predictedArrival,
                                    String confidence, String basis) {
        int rows = jdbc.sql("""
                update shipment
                set predicted_arrival = :arrival, predicted_confidence = :confidence, prediction_basis = :basis,
                    updated_at = now(), version = version + 1
                where id = :id and version = :version
                """)
                .param("id", shipmentId)
                .param("version", expectedVersion)
                .param("arrival", predictedArrival)
                .param("confidence", confidence)
                .param("basis", basis)
                .update();
        return rows == 1;
    }

    public List<String> skusOnShipment(long shipmentId) {
        return jdbc.sql("""
                select sku.code from shipment s
                join purchase_order_line l on l.po_id = s.po_id
                join sku on sku.id = l.sku_id
                where s.id = :id order by sku.code
                """).param("id", shipmentId).query(String.class).list();
    }

    public record LaneOf(String originPort, String destFcCode, int receivingBufferDays) {}

    public LaneOf laneOf(long shipmentId) {
        return jdbc.sql("""
                select po.origin_port, fc.code as dest_fc_code, fc.receiving_buffer_days
                from shipment s
                join purchase_order po on po.id = s.po_id
                join fulfillment_center fc on fc.id = po.dest_fc_id
                where s.id = :id
                """)
                .param("id", shipmentId)
                .query(LaneOf.class)
                .single();
    }

    private Shipment mapShipment(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date predicted = rs.getDate("predicted_arrival");
        return new Shipment(
                rs.getLong("id"),
                rs.getLong("po_id"),
                rs.getString("carrier"),
                rs.getString("container_no"),
                rs.getDate("planned_departure").toLocalDate(),
                rs.getDate("planned_arrival").toLocalDate(),
                predicted == null ? null : predicted.toLocalDate(),
                rs.getString("predicted_confidence"),
                rs.getString("prediction_basis"),
                MilestoneType.valueOf(rs.getString("current_stage")),
                rs.getInt("version"));
    }

    private ShipmentMilestone mapMilestone(ResultSet rs, int rowNum) throws SQLException {
        return new ShipmentMilestone(
                rs.getLong("id"),
                rs.getLong("shipment_id"),
                MilestoneType.valueOf(rs.getString("type")),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("source"),
                rs.getObject("event_id", UUID.class),
                rs.getObject("recorded_at", OffsetDateTime.class));
    }
}
