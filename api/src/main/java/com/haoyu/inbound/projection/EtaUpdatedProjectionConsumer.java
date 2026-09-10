package com.haoyu.inbound.projection;

import com.haoyu.inbound.events.EtaUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Second consumer of shipment.eta-updated: keeps the storefront projection current. */
@Component
class EtaUpdatedProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(EtaUpdatedProjectionConsumer.class);

    private final AvailabilityProjectionService projection;
    private final ObjectMapper json;

    EtaUpdatedProjectionConsumer(AvailabilityProjectionService projection, ObjectMapper json) {
        this.projection = projection;
        this.json = json;
    }

    @KafkaListener(topics = "${app.topics.eta-updated}", groupId = "availability-projector")
    void onEtaUpdated(String payload) {
        EtaUpdatedEvent event = json.readValue(payload, EtaUpdatedEvent.class);
        var summary = projection.projectForShipment(event.shipmentId());
        log.info("shipment {} eta {} -> re-projected {} storefront rows", event.shipmentId(), event.predictedArrival(), summary.items());
    }
}
