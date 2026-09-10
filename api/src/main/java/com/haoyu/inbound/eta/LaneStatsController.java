package com.haoyu.inbound.eta;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LaneStatsController {

    private final LaneStatsRepository laneStats;
    private final EtaRecalculationService recalculation;

    LaneStatsController(LaneStatsRepository laneStats, EtaRecalculationService recalculation) {
        this.laneStats = laneStats;
        this.recalculation = recalculation;
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
        return recalculation.recalculateAllOpen();
    }
}
