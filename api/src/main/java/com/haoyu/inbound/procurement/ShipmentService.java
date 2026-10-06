package com.haoyu.inbound.procurement;

import com.haoyu.inbound.common.AppProperties;
import com.haoyu.inbound.common.ConflictException;
import com.haoyu.inbound.events.EventPublisher;
import com.haoyu.inbound.events.ShipmentMilestoneEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentService {

    /** Carrier clocks drift; anything further in the future than this is a data error, not a milestone. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);

    public enum Outcome { RECORDED, DUPLICATE }

    private final ShipmentRepository shipments;
    private final EventPublisher publisher;
    private final AppProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public ShipmentService(ShipmentRepository shipments, EventPublisher publisher, AppProperties props, Clock clock,
                           MeterRegistry meters) {
        this.shipments = shipments;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Records a carrier / customs / FC gate-in milestone and the event that tells the rest of the system,
     * in one transaction (the event goes through the outbox). Idempotent: the same event id, or the same
     * stage at the same time, is a DUPLICATE and nothing else runs. The same stage at a different time is
     * a conflicting report and is refused (409) rather than silently ignored or overwritten.
     */
    @Transactional
    public Outcome recordMilestone(long shipmentId, MilestoneType type, OffsetDateTime occurredAt, MilestoneSource source, UUID eventId) {
        if (occurredAt.isAfter(OffsetDateTime.now(clock).plus(MAX_CLOCK_SKEW))) {
            throw new IllegalArgumentException("occurredAt " + occurredAt + " is in the future");
        }
        shipments.require(shipmentId);
        if (!shipments.isPurchaseOrderOpen(shipmentId)) {
            throw new ConflictException("the purchase order of shipment " + shipmentId + " is already received or cancelled");
        }
        shipments.latestEarlierMilestone(shipmentId, type).ifPresent(previous -> {
            if (occurredAt.isBefore(previous)) {
                throw new IllegalArgumentException(type + " at " + occurredAt + " is earlier than the previous stage at " + previous);
            }
        });
        if (!shipments.insertMilestoneIfNew(shipmentId, type, occurredAt, source.name(), eventId)) {
            var existing = shipments.findMilestone(shipmentId, type, eventId).orElseThrow();
            boolean sameReport = existing.eventId().equals(eventId) || existing.occurredAt().isEqual(occurredAt);
            if (sameReport) return Outcome.DUPLICATE;
            throw new ConflictException("%s for shipment %d was already reported at %s; refusing a conflicting report at %s"
                    .formatted(type, shipmentId, existing.occurredAt(), occurredAt));
        }
        shipments.advanceStage(shipmentId, type);
        publisher.publish(props.topics().milestones(), Long.toString(shipmentId),
                new ShipmentMilestoneEvent(eventId, shipmentId, type, occurredAt, source.name()));
        meters.counter("milestones.recorded", "type", type.name(), "source", source.name()).increment();
        return Outcome.RECORDED;
    }
}
