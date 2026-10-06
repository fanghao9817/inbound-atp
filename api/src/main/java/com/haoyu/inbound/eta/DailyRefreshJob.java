package com.haoyu.inbound.eta;

import com.haoyu.inbound.projection.AvailabilityProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Predictions used to move only when a milestone arrived, so an overdue container kept its old date
 * (and its HIGH confidence) for weeks. Just after midnight, when "today" moves, every open container
 * is re-scored and the storefront projection is rebuilt.
 */
@Component
class DailyRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(DailyRefreshJob.class);

    private final EtaRecalculationService recalculation;
    private final AvailabilityProjectionService projection;

    DailyRefreshJob(EtaRecalculationService recalculation, AvailabilityProjectionService projection) {
        this.recalculation = recalculation;
        this.projection = projection;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Vancouver")
    void run() {
        var rescored = recalculation.recalculateAllOpen();
        var projected = projection.projectAll();
        log.info("daily refresh: {} open containers re-scored ({} changed), {} storefront rows projected",
                rescored.shipments(), rescored.changed(), projected.items());
    }
}
