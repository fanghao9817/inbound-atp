package com.haoyu.inbound.common;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param businessZone  the zone that defines "today" for promises, KPIs and daily jobs (Vancouver)
 * @param internalToken shared secret for /api/internal/** (operator and external-system writes)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Topics topics, Seed seed, Atp atp, Aws aws, Orders orders, ZoneId businessZone, String internalToken) {

    public record Topics(String milestones, String etaUpdated, String availabilityChanged) {}

    public record Seed(boolean enabled) {}

    public record Atp(int horizonDays) {}

    public record Aws(String region, String projectorFunction, String availabilityUrl) {

        public boolean projectionEnabled() {
            return projectorFunction != null && !projectorFunction.isBlank();
        }
    }

    /**
     * @param allocationWindowDays a scheduled (B2B) order is turned into a reservation once its need-by
     *                             date is this close, so stock is not locked weeks before it ships
     * @param publicMaxQty         largest quantity a visitor can order through the public endpoint
     */
    public record Orders(int allocationWindowDays, int publicMaxQty) {}
}
