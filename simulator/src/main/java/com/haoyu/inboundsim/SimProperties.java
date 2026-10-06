package com.haoyu.inboundsim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param goLive ISO instant at which the simulator took over (blank = first start). Containers already at sea before it get their next
 *               stage drawn conditional on "not before go-live"; everything after is drawn freely.
 */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(boolean enabled, String apiBaseUrl, String token, String seed, String goLive,
                            double baseWeeklyUnits, String heartbeatFile) {}
