package com.haoyu.inboundsim;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The outside world of inbound-atp, simulated: carriers and customs brokers, the warehouse, online
 * and B2B customers, and the buyer. It talks to the API exactly like those systems would - over HTTP,
 * with the internal token - and keeps no state of its own: every decision is a deterministic function
 * of a secret seed, the clock and what the API reports.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Go-live must be stable across restarts (it anchors the conditioning of pre-existing containers). */
    @Bean
    GoLive goLive(SimProperties props, Clock clock) {
        boolean configured = props.goLive() != null && !props.goLive().isBlank();
        Instant at = configured ? Instant.parse(props.goLive().trim()) : clock.instant();
        if (!configured) log.warn("SIM_GO_LIVE not set; using startup time {} (set it in infra/.env)", at);
        log.info("simulator go-live {}, seed hash {}", at, Integer.toHexString(props.seed().hashCode()));
        return new GoLive(at);
    }

    public record GoLive(Instant at) {}
}
