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
    private final java.time.Clock clock;

    LaneStatsController(LaneStatsRepository laneStats, RefreshService refresh, java.time.Clock clock) {
        this.laneStats = laneStats;
        this.refresh = refresh;
        this.clock = clock;
    }

    /** What dbt computed; empty until the first `dbt run`. */
    @GetMapping("/api/lanes/stats")
    List<LaneStats> stats() {
        return laneStats.listAll();
    }

    /**
     * When the last full refresh finished (daily at 00:05 and after each nightly dbt run), and when dbt
     * last rebuilt the lane statistics - the external watchdog alerts when that is more than a day old.
     */
    @GetMapping("/api/refresh/last")
    Map<String, Object> last() {
        var s = refresh.last();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (s != null) {
            out.put("finishedAt", s.finishedAt());
            out.put("rescored", s.rescored());
            out.put("projected", s.projected());
        }
        var computedAt = laneStats.computedAt();
        out.put("statsComputedAt", computedAt);
        out.put("statsAgeMinutes", computedAt == null ? null : java.time.Duration.between(computedAt.toInstant(), clock.instant()).toMinutes());
        return out;
    }

    /** After a dbt refresh: re-score every open shipment against the new statistics, then re-project. */
    @PostMapping("/api/internal/eta/recalculate-all")
    RefreshService.Summary recalculateAll() {
        return refresh.refresh(EtaRecalculationService.Reason.STATS_REFRESH);
    }
}
