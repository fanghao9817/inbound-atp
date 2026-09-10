package com.haoyu.inbound.events;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Published on shipment.eta-updated whenever a shipment's predicted arrival or confidence changes. */
public record EtaUpdatedEvent(long shipmentId, long poId, LocalDate previousArrival, LocalDate predictedArrival,
                              String confidence, String basis, OffsetDateTime computedAt) {}
