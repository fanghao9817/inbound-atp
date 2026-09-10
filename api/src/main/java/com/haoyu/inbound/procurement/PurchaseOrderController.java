package com.haoyu.inbound.procurement;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderQueryRepository queries;

    PurchaseOrderController(PurchaseOrderQueryRepository queries) {
        this.queries = queries;
    }

    @GetMapping
    List<PurchaseOrderQueryRepository.PurchaseOrderView> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "200") int limit) {
        String s = "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
        return queries.list(s, Math.min(limit, 1000));
    }
}
