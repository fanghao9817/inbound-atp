package com.haoyu.inbound.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Topics topics, Seed seed, Atp atp) {

    public record Topics(String milestones, String etaUpdated) {}

    public record Seed(boolean enabled) {}

    public record Atp(int horizonDays) {}
}
