package com.haoyu.inbound.procurement;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.events.EventPublisher;
import com.haoyu.inbound.events.ShipmentMilestoneEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final EventPublisher publisher;
    private final AppProperties props;

    public ShipmentService(ShipmentRepository shipments, EventPublisher publisher, AppProperties props) {
        this.shipments = shipments;
        this.publisher = publisher;
        this.props = props;
    }

    /**
     * Records a carrier/customs/FC milestone and, once it is committed, tells the rest of the system.
     * Returns false for a replayed event so the caller can answer idempotently.
     */
    @Transactional
    public boolean recordMilestone(long shipmentId, MilestoneType type, OffsetDateTime occurredAt,
                                   String source, UUID eventId) {
        shipments.require(shipmentId);
        boolean isNew = shipments.insertMilestoneIfNew(shipmentId, type, occurredAt, source, eventId);
        if (!isNew) {
            return false;
        }
        shipments.advanceStage(shipmentId, type);
        publisher.publishAfterCommit(props.topics().milestones(), Long.toString(shipmentId),
                new ShipmentMilestoneEvent(eventId, shipmentId, type, occurredAt, source));
        return true;
    }
}
