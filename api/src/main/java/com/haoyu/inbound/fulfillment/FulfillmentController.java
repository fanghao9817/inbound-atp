package com.haoyu.inbound.fulfillment;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FulfillmentController {

    private final FulfillmentService service;

    FulfillmentController(FulfillmentService service) {
        this.service = service;
    }

    /** POST /api/fulfillment/quote {"sku":"SOFA-3S-OAT","fc":"FC-RIC","requestedQuantity":120} */
    @PostMapping("/api/fulfillment/quote")
    FulfillmentResponse quote(@Valid @RequestBody FulfillmentRequest request) {
        return service.quote(request);
    }
}
