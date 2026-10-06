package com.haoyu.inbound.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Transactional outbox, read side. Every half second it claims up to 100 unpublished rows in id order
 * (FOR UPDATE SKIP LOCKED, so a second relay instance would take different rows instead of blocking),
 * sends each synchronously and marks it published. It stops at the first failure so events for the
 * same key never overtake each other, then backs off (1 s doubling to 30 s) while Kafka is down,
 * logging once per outage. A row that fails 20 times is parked so it cannot block everything behind it.
 * Delivery is at-least-once: a crash after send but before the mark re-sends that row, which the
 * consumers tolerate because they recompute from database state.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;
    private static final int PARK_AFTER_ATTEMPTS = 20;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private record Pending(long id, String topic, String msgKey, String payload, int attempts) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private Duration backoff = Duration.ZERO;
    private Instant nextAttempt = Instant.MIN;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.clock = clock;
        Gauge.builder("outbox.pending", () -> count("published_at is null and parked_at is null"))
                .description("events committed but not yet published to Kafka").register(meters);
        Gauge.builder("outbox.parked", () -> count("parked_at is not null"))
                .description("events that failed repeatedly and need a look").register(meters);
    }

    private long count(String where) {
        return jdbc.sql("select count(*) from outbox_event where " + where).query(Long.class).single();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
    void relay() {
        if (clock.instant().isBefore(nextAttempt)) return;
        Integer sent;
        do {
            sent = tx.execute(status -> relayBatch());
        } while (sent != null && sent == BATCH);
        if (sent != null && sent >= 0 && !backoff.isZero()) {
            log.info("outbox relay recovered; publishing again");
            backoff = Duration.ZERO;
        }
    }

    private int relayBatch() {
        List<Pending> batch = jdbc.sql("""
                select id, topic, msg_key, payload, attempts
                from outbox_event
                where published_at is null and parked_at is null
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
                boolean park = p.attempts() + 1 >= PARK_AFTER_ATTEMPTS;
                jdbc.sql("""
                        update outbox_event set attempts = attempts + 1, last_error = :err,
                               parked_at = case when :park then now() end
                        where id = :id
                        """)
                        .param("err", String.valueOf(e.getMessage())).param("park", park).param("id", p.id())
                        .update();
                if (backoff.isZero()) log.warn("outbox relay paused at event {} ({}): {}", p.id(), p.topic(), e.toString());
                if (park) log.error("outbox event {} parked after {} attempts", p.id(), PARK_AFTER_ATTEMPTS);
                backoff = backoff.isZero() ? Duration.ofSeconds(1) : min(backoff.multipliedBy(2), MAX_BACKOFF);
                nextAttempt = clock.instant().plus(backoff);
                return -1;
            }
            jdbc.sql("update outbox_event set published_at = now(), attempts = attempts + 1 where id = :id")
                    .param("id", p.id())
                    .update();
            sent++;
        }
        return sent;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Published rows are only kept for a week, for debugging. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Vancouver")
    void purge() {
        int n = jdbc.sql("delete from outbox_event where published_at < now() - interval '7 days'").update();
        if (n > 0) log.info("outbox purge: {} published events older than 7 days removed", n);
    }
}
