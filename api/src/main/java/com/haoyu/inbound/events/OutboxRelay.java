package com.haoyu.inbound.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox, read side - deliberately simple: one relay, at-least-once.
 * Every half second it claims up to 100 unpublished rows in id order (FOR UPDATE SKIP LOCKED), sends
 * each synchronously and marks it published. It stops at the first failure, so events for the same
 * key never overtake each other; while Kafka is down each attempt gives up within ~5 s (producer
 * timeouts in application.yml) and the outage is logged once. A crash after send but before the mark
 * re-sends that row - harmless, because every consumer recomputes from the database.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private record Pending(long id, String topic, String msgKey, String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private volatile boolean failing;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        Gauge.builder("outbox.pending", () -> jdbc.sql("select count(*) from outbox_event where published_at is null")
                        .query(Long.class).single())
                .description("events committed but not yet published to Kafka").register(meters);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
    void relay() {
        Integer sent;
        do {
            sent = tx.execute(status -> relayBatch());
        } while (sent != null && sent == BATCH);
    }

    private int relayBatch() {
        List<Pending> batch = jdbc.sql("""
                select id, topic, msg_key, payload
                from outbox_event
                where published_at is null
                order by id
                limit :batch
                for update skip locked
                """)
                .param("batch", BATCH)
                .query(Pending.class)
                .list();
        int sent = 0;
        for (Pending p : batch) {
            try {
                kafka.send(p.topic(), p.msgKey(), p.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                jdbc.sql("update outbox_event set attempts = attempts + 1, last_error = :err where id = :id")
                        .param("err", String.valueOf(e.getMessage())).param("id", p.id())
                        .update();
                if (!failing) log.warn("outbox relay paused at event {} ({}): {}", p.id(), p.topic(), e.toString());
                failing = true;
                return -1;
            }
            jdbc.sql("update outbox_event set published_at = now(), attempts = attempts + 1 where id = :id")
                    .param("id", p.id())
                    .update();
            sent++;
        }
        if (failing && sent > 0) {
            log.info("outbox relay recovered");
            failing = false;
        }
        return sent;
    }

    /** Published rows are kept for a week, for debugging. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Vancouver")
    void purge() {
        int n = jdbc.sql("delete from outbox_event where published_at < now() - interval '7 days'").update();
        if (n > 0) log.info("outbox purge: {} published events older than 7 days removed", n);
    }
}
