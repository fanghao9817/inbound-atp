package com.haoyu.inbound.procurement;

import com.haoyu.inbound.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
     * Records a milestone exactly once per event id. Returns false when the event was already
     * recorded (replayed EDI message, at-least-once delivery) so callers can skip side effects.
     */
    public boolean insertMilestoneIfNew(long shipmentId, MilestoneType type, OffsetDateTime occurredAt,
                                        String source, UUID eventId) {
        try {
            int rows = jdbc.sql("""
                    insert into shipment_milestone (shipment_id, type, occurred_at, source, event_id)
                    values (:shipment, :type, :occurredAt, :source, :eventId)
                    on conflict (shipment_id, type) do nothing
                    """)
                    .param("shipment", shipmentId)
                    .param("type", type.name())
                    .param("occurredAt", occurredAt)
                    .param("source", source)
                    .param("eventId", eventId)
                    .update();
            return rows == 1;
        } catch (DuplicateKeyException sameEventId) {
            return false;
        }
    }

    /** Moves the stage forward only; milestones can arrive out of order and must not regress it. */
    public void advanceStage(long shipmentId, MilestoneType stage) {
        jdbc.sql("""
                update shipment
                set current_stage = :stage, updated_at = now(), version = version + 1
                where id = :id
                  and array_position(array['BOOKED','DEPARTED_ORIGIN','ARRIVED_DEST_PORT','CUSTOMS_CLEARED','RECEIVED_FC'], current_stage)
                    < array_position(array['BOOKED','DEPARTED_ORIGIN','ARRIVED_DEST_PORT','CUSTOMS_CLEARED','RECEIVED_FC'], :stage)
                """)
                .param("id", shipmentId)
                .param("stage", stage.name())
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
