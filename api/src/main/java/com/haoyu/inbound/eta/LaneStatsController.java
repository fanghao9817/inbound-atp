package com.haoyu.inbound.eta;

import com.haoyu.inbound.projection.AvailabilityProjectionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LaneStatsController {

    private final LaneStatsRepository laneStats;
    private final EtaRecalculationService recalculation;
    private final AvailabilityProjectionService projection;

    LaneStatsController(LaneStatsRepository laneStats, EtaRecalculationService recalculation,
                        AvailabilityProjectionService projection) {
        this.laneStats = laneStats;
        this.recalculation = recalculation;
        this.projection = projection;
    }

    /** What dbt computed; empty until the first `dbt run`. */
    @GetMapping("/api/lanes/stats")
    List<LaneStats> stats() {
        return laneStats.listAll();
    }

    /**
     * Re-scores every open shipment against the current lane statistics. Run after a dbt refresh;
     * milestones trigger recalculation on their own.
     */
    @PostMapping("/api/eta/recalculate-all")
    EtaRecalculationService.RecalcSummary recalculateAll() {
        var summary = recalculation.recalculateAllOpen();
        projection.projectAll();
        return summary;
    }
}
