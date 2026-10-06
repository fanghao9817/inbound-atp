package com.haoyu.inbound.events;

import com.haoyu.inbound.common.AppProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Topics are declared in code so a fresh broker (tests, a rebuilt box) gets the same layout. */
@Configuration
class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics topics(AppProperties props) {
        var t = props.topics();
        return new KafkaAdmin.NewTopics(
                topic(t.milestones()), dlt(t.milestones()),
                topic(t.etaUpdated()), dlt(t.etaUpdated()),
                topic(t.availabilityChanged()), dlt(t.availabilityChanged()));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    /** Dead-letter topic: messages a consumer gave up on, kept 30 days for inspection and replay. */
    private static NewTopic dlt(String name) {
        return TopicBuilder.name(name + KafkaErrorHandlingConfig.DLT_SUFFIX).partitions(1).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
