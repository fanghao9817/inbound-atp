package com.haoyu.inbound;

import com.haoyu.inbound.projection.AvailabilityItem;
import com.haoyu.inbound.projection.ProjectionSink;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Captures projection batches instead of invoking the Lambda. */
@TestConfiguration(proxyBeanMethods = false)
public class TestProjectionConfig {

    public static class RecordingSink implements ProjectionSink {
        public final List<Map.Entry<String, List<AvailabilityItem>>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void push(String source, List<AvailabilityItem> items) {
            batches.add(Map.entry(source, List.copyOf(items)));
        }
    }

    @Bean
    @Primary
    RecordingSink recordingSink() {
        return new RecordingSink();
    }
}
