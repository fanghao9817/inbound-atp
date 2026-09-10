package com.haoyu.inbound.eta;

import com.haoyu.inbound.procurement.MilestoneType;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LaneStatsController {

    public record RecalcSummary(int shipments, int changed) {}

    private final LaneStatsRepository laneStats;
    private final EtaRecalculationService recalculation;
    private final JdbcClient jdbc;

    LaneStatsController(LaneStatsRepository laneStats, EtaRecalculationService recalculation, JdbcClient jdbc) {
        this.laneStats = laneStats;
        this.recalculation = recalculation;
        this.jdbc = jdbc;
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
    RecalcSummary recalculateAll() {
        List<Long> ids = jdbc.sql("""
                select s.id from shipment s join purchase_order po on po.id = s.po_id
                where po.status = 'OPEN' and s.current_stage <> :received order by s.id
                """)
                .param("received", MilestoneType.RECEIVED_FC.name())
                .query(Long.class)
                .list();
        int changed = 0;
        for (long id : ids) {
            var before = jdbc.sql("select predicted_arrival from shipment where id = :id").param("id", id)
                    .query(java.time.LocalDate.class).optional().orElse(null);
            var after = recalculation.recalculate(id).arrival();
            if (!java.util.Objects.equals(before, after)) changed++;
        }
        return new RecalcSummary(ids.size(), changed);
    }
}
