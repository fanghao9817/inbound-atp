package com.haoyu.inbound.events;

import com.haoyu.inbound.procurement.MilestoneType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Published on shipment.milestones after a milestone has been durably recorded. Key = shipmentId. */
public record ShipmentMilestoneEvent(UUID eventId, long shipmentId, MilestoneType type, OffsetDateTime occurredAt,
                                     String source) {}
