package com.haoyu.inbound.events;

import com.haoyu.inbound.common.NotFoundException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * Consumer error policy: a failing record is retried 5 times with exponential backoff (1, 2, 4, 8, 16 s,
 * capped at 30 s - about half a minute, enough to ride out a database restart, and well under
 * max.poll.interval), then published to "&lt;topic&gt;.dlt" and skipped, so one poison message cannot
 * block its partition. Malformed JSON, invalid arguments and unknown ids go to the DLT at once:
 * retrying them cannot help. DLT records are kept 30 days (see KafkaTopicsConfig).
 */
@Configuration
class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".dlt";

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));   // -1: let Kafka pick the partition
        var backoff = new ExponentialBackOffWithMaxRetries(5);
        backoff.setInitialInterval(1_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(30_000L);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class, NotFoundException.class);
        return handler;
    }
}
