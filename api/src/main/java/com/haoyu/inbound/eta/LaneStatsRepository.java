package com.haoyu.inbound.eta;

import com.haoyu.inbound.procurement.MilestoneType;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LaneStatsRepository {

    private static final String SELECT = """
            select origin_port, dest_fc_code, from_stage, to_stage, p50_days, p80_days, sample_n
            from analytics.lane_lead_time_stats
            """;

    private final JdbcClient jdbc;

    public LaneStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LaneStats> find(String originPort, String destFcCode, MilestoneType from, MilestoneType to) {
        return jdbc.sql(SELECT + " where origin_port = :o and dest_fc_code = :d and from_stage = :f and to_stage = :t")
                .param("o", originPort)
                .param("d", destFcCode)
                .param("f", from.name())
                .param("t", to.name())
                .query(LaneStats.class)
                .optional();
    }

    public List<LaneStats> listAll() {
        return jdbc.sql(SELECT + " order by origin_port, dest_fc_code, from_stage").query(LaneStats.class).list();
    }
}
