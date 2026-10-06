package com.haoyu.inbound.projection;

import com.haoyu.inbound.events.AvailabilityChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the storefront projection current. Its single trigger is availability.changed (stock,
 * commitments or an ETA changed for one SKU x FC, key "SKU|FC"), so all updates to one position are
 * serialized on one partition. It recomputes from the database, so duplicates are harmless.
 */
@Component
class AvailabilityProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityProjectionConsumer.class);

    private final AvailabilityProjectionService projection;
    private final ObjectMapper json;

    AvailabilityProjectionConsumer(AvailabilityProjectionService projection, ObjectMapper json) {
        this.projection = projection;
        this.json = json;
    }

    @KafkaListener(topics = "${app.topics.availability-changed}", groupId = "availability-projector")
    void onAvailabilityChanged(String payload) {
        AvailabilityChangedEvent event = json.readValue(payload, AvailabilityChangedEvent.class);
        projection.projectPosition(event.sku(), event.fc());
        log.debug("{} at {} ({}) -> re-projected", event.sku(), event.fc(), event.reason());
    }
}
