package com.haoyu.inbound.catalog;

import com.haoyu.inbound.common.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Sku> findSkuByCode(String code) {
        return jdbc.sql("select id, code, name, category from sku where code = :code")
                .param("code", code)
                .query(Sku.class)
                .optional();
    }

    public Sku requireSku(String code) {
        return findSkuByCode(code).orElseThrow(() -> new NotFoundException("sku", code));
    }

    public List<Sku> listSkus() {
        return jdbc.sql("select id, code, name, category from sku order by code").query(Sku.class).list();
    }

    public Optional<FulfillmentCenter> findFcByCode(String code) {
        return jdbc.sql("select id, code, name, region, receiving_buffer_days from fulfillment_center where code = :code")
                .param("code", code)
                .query(FulfillmentCenter.class)
                .optional();
    }

    public FulfillmentCenter requireFc(String code) {
        return findFcByCode(code).orElseThrow(() -> new NotFoundException("fulfillment center", code));
    }

    public List<FulfillmentCenter> listFcs() {
        return jdbc.sql("select id, code, name, region, receiving_buffer_days from fulfillment_center order by code")
                .query(FulfillmentCenter.class)
                .list();
    }
}
