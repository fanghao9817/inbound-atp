package com.haoyu.inbound.eta;

import com.haoyu.inbound.events.ShipmentMilestoneEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class EtaRecalculationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EtaRecalculationConsumer.class);

    private final EtaRecalculationService service;
    private final ObjectMapper json;

    EtaRecalculationConsumer(EtaRecalculationService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = "${app.topics.milestones}", groupId = "${spring.kafka.consumer.group-id}")
    void onMilestone(String payload) {
        ShipmentMilestoneEvent event = json.readValue(payload, ShipmentMilestoneEvent.class);
        var prediction = service.recalculate(event.shipmentId());
        log.info("shipment {} {} -> predicted {} ({})", event.shipmentId(), event.type(),
                prediction.arrival(), prediction.confidence());
    }
}
