package com.haoyu.inbound.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Topics topics, Seed seed, Atp atp, Aws aws) {

    public record Topics(String milestones, String etaUpdated) {}

    public record Seed(boolean enabled) {}

    public record Atp(int horizonDays) {}

    public record Aws(String region, String projectorFunction, String availabilityUrl) {

        public boolean projectionEnabled() {
            return projectorFunction != null && !projectorFunction.isBlank();
        }
    }
}
