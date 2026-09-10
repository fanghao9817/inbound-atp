package com.haoyu.inbound.eta;

import java.math.BigDecimal;

/** Historical transit-time distribution for a lane and stage pair, computed by dbt. */
public record LaneStats(String originPort, String destFcCode, String fromStage, String toStage,
                        BigDecimal p50Days, BigDecimal p80Days, int sampleN) {}
