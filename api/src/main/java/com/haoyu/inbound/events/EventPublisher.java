package com.haoyu.inbound.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox, write side: an event is a row inserted in the same database transaction as
 * the state change it describes. If the transaction rolls back, the event never existed; if it
 * commits, {@link OutboxRelay} is guaranteed to deliver it to Kafka (at least once), even if the
 * process crashes right after the commit.
 */
@Component
public class EventPublisher {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public EventPublisher(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must run inside the business transaction; MANDATORY fails fast if someone calls it outside one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, Object payload) {
        jdbc.sql("insert into outbox_event (topic, msg_key, payload) values (:topic, :key, :payload)")
                .param("topic", topic)
                .param("key", key)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
