package com.haoyu.inbound.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes JSON events to Kafka <em>after</em> the surrounding database transaction commits, so a
 * consumer can never observe an event whose row was rolled back. (A full transactional outbox
 * would also survive a crash between commit and send; documented as the next step in the README.)
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishAfterCommit(String topic, String key, Object payload) {
        String body = json.writeValueAsString(payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(topic, key, body);
                }
            });
        } else {
            send(topic, key, body);
        }
    }

    private void send(String topic, String key, String body) {
        kafka.send(topic, key, body).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("failed to publish to {} key={}", topic, key, ex);
            } else {
                log.debug("published to {} key={} partition={} offset={}", topic, key,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        });
    }
}
