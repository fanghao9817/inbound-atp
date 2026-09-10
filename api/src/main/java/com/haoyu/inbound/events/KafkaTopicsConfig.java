package com.haoyu.inbound.events;

import com.haoyu.inbound.common.AppProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicsConfig {

    @Bean
    NewTopic milestonesTopic(AppProperties props) {
        return TopicBuilder.name(props.topics().milestones()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic etaUpdatedTopic(AppProperties props) {
        return TopicBuilder.name(props.topics().etaUpdated()).partitions(3).replicas(1).build();
    }
}
