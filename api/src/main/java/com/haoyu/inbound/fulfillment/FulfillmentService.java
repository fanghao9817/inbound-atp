package com.haoyu.inbound.fulfillment;

import com.haoyu.inbound.catalog.CatalogRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class FulfillmentService {

    private final CatalogRepository catalog;
    private final FulfillmentJdbcDao dao;
    private final Clock clock;

    public FulfillmentService(CatalogRepository catalog, FulfillmentJdbcDao dao, Clock clock) {
        this.catalog = catalog;
        this.dao = dao;
        this.clock = clock;
    }

    /** Read-only quote: nothing is reserved or updated. */
    public FulfillmentResponse quote(FulfillmentRequest request) {
        catalog.requireSku(request.sku());
        if (request.fc() != null) {
            catalog.requireFc(request.fc());
        }
        FulfillmentJdbcDao.Snapshot snapshot = dao.snapshot(request.sku(), request.fc());
        return AllocationPlanner.plan(request.sku(), request.fc(), request.requestedQuantity(),
                snapshot.inventoryAvailable(), snapshot.inbound(), LocalDate.now(clock));
    }
}
