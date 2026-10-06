package com.haoyu.inbound.procurement;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PurchaseOrderController {

    private final PurchaseOrderQueryRepository queries;
    private final PurchaseOrderService service;

    PurchaseOrderController(PurchaseOrderQueryRepository queries, PurchaseOrderService service) {
        this.queries = queries;
        this.service = service;
    }

    /** Buyer's system: 201 for a new PO, 200 when clientRef was already used (the original PO is returned). */
    @PostMapping("/api/internal/purchase-orders")
    ResponseEntity<PurchaseOrderService.Created> create(@RequestBody PurchaseOrderService.CreatePurchaseOrder cmd) {
        var created = service.create(cmd);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created);
    }

    @GetMapping("/api/purchase-orders")
    List<PurchaseOrderQueryRepository.PurchaseOrderView> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "200") int limit) {
        String s = "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
        return queries.list(s, Math.min(limit, 1000));
    }
}
