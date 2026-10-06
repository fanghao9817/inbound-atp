package com.haoyu.inbound.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** Every "today" in the API comes from this clock, in the business zone - never from the JVM or DB default. */
    @Bean
    Clock clock(AppProperties props) {
        return Clock.system(props.businessZone());
    }
}
