package com.haoyu.inbound.procurement;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShipmentMilestone(long id, long shipmentId, MilestoneType type, OffsetDateTime occurredAt,
                                String source, UUID eventId, OffsetDateTime recordedAt) {}
