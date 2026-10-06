package com.haoyu.inbound.eta;

import com.haoyu.inbound.common.ConflictException;
import com.haoyu.inbound.projection.AvailabilityProjectionService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Full refresh = re-score every open container, then re-project every SKU x FC to the storefront.
 * Runs every day just after midnight (Vancouver), when "today" moves and overdue containers must be
 * re-estimated, and after each dbt refresh. Single-flight: a second caller gets 409 instead of a
 * second concurrent run.
 */
@Service
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    public record Summary(EtaRecalculationService.RecalcSummary rescored, int projected, Instant finishedAt) {}

    private final EtaRecalculationService recalculation;
    private final AvailabilityProjectionService projection;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Summary last;

    public RefreshService(EtaRecalculationService recalculation, AvailabilityProjectionService projection, Clock clock) {
        this.recalculation = recalculation;
        this.projection = projection;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Vancouver")
    void daily() {
        Summary s = refresh(EtaRecalculationService.Reason.DAILY);
        log.info("daily refresh: {} containers re-scored ({} changed, {} failed), {} storefront rows projected",
                s.rescored().shipments(), s.rescored().changed(), s.rescored().failed(), s.projected());
    }

    public Summary refresh(EtaRecalculationService.Reason reason) {
        if (!running.compareAndSet(false, true)) {
            throw new ConflictException("a refresh is already running");
        }
        try {
            var rescored = recalculation.recalculateAllOpen(reason);
            var projected = projection.projectAll();
            last = new Summary(rescored, projected.items(), clock.instant());
            return last;
        } finally {
            running.set(false);
        }
    }

    public Summary last() {
        return last;
    }
}
