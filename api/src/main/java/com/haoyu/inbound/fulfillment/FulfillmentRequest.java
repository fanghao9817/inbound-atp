package com.haoyu.inbound.fulfillment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** @param fc optional fulfillment-center code; when null the quote aggregates every FC */
public record FulfillmentRequest(@NotBlank String sku, String fc, @Positive int requestedQuantity) {}
