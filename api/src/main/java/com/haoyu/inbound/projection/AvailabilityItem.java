package com.haoyu.inbound.projection;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One SKU x FC row of the storefront projection, as accepted by the projector Lambda. */
public record AvailabilityItem(String sku, String fc, String fcName, int availableNow, LocalDate promiseDate,
                               boolean promisable, String confidence, LocalDate nextArrival, OffsetDateTime updatedAt,
                               String source) {}
