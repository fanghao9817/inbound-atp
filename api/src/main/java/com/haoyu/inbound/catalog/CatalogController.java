package com.haoyu.inbound.catalog;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

    private final CatalogRepository catalog;

    CatalogController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/skus")
    List<Sku> skus() {
        return catalog.listSkus();
    }

    @GetMapping("/api/fulfillment-centers")
    List<FulfillmentCenter> fulfillmentCenters() {
        return catalog.listFcs();
    }
}
