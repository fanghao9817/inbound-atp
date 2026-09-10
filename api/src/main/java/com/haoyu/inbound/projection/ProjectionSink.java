package com.haoyu.inbound.projection;

import java.util.List;

/** Where projection batches go. Production: the projector Lambda; tests: an in-memory recorder. */
public interface ProjectionSink {

    void push(String source, List<AvailabilityItem> items);
}
