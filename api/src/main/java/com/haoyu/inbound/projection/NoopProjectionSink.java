package com.haoyu.inbound.projection;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Used when no projector function is configured: projection is computed but goes nowhere. */
@Configuration
class NoopProjectionSink {

    private static final Logger log = LoggerFactory.getLogger(NoopProjectionSink.class);

    @Bean
    @ConditionalOnMissingBean(ProjectionSink.class)
    ProjectionSink noopSink() {
        return (source, items) -> log.debug("projection {}: {} items (no sink configured)", source, items.size());
    }
}
