package com.haoyu.inbound.events;

import com.haoyu.inbound.common.AppProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/** Announces that an ATP input changed for one SKU at one FC (written to the outbox, same transaction). */
@Component
public class AvailabilityEvents {

    private final EventPublisher publisher;
    private final AppProperties props;
    private final Clock clock;

    public AvailabilityEvents(EventPublisher publisher, AppProperties props, Clock clock) {
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    public void changed(String sku, String fc, String reason) {
        publisher.publish(props.topics().availabilityChanged(), AvailabilityChangedEvent.key(sku, fc),
                new AvailabilityChangedEvent(sku, fc, reason, OffsetDateTime.now(clock)));
    }
}
