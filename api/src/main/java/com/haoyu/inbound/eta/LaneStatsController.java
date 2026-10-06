package com.haoyu.inbound.eta;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LaneStatsController {

    private final LaneStatsRepository laneStats;
    private final RefreshService refresh;

    LaneStatsController(LaneStatsRepository laneStats, RefreshService refresh) {
        this.laneStats = laneStats;
        this.refresh = refresh;
    }

    /** What dbt computed; empty until the first `dbt run`. */
    @GetMapping("/api/lanes/stats")
    List<LaneStats> stats() {
        return laneStats.listAll();
    }

    /** When the last full refresh finished (daily at 00:05 and after each dbt run). */
    @GetMapping("/api/refresh/last")
    Map<String, Object> last() {
        var s = refresh.last();
        return s == null ? Map.of() : Map.of("finishedAt", s.finishedAt(), "rescored", s.rescored(), "projected", s.projected());
    }

    /** After a dbt refresh: re-score every open shipment against the new statistics, then re-project. */
    @PostMapping("/api/internal/eta/recalculate-all")
    RefreshService.Summary recalculateAll() {
        return refresh.refresh(EtaRecalculationService.Reason.STATS_REFRESH);
    }
}
