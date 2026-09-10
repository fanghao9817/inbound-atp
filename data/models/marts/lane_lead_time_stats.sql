-- Contract table read by the API (see V1__schema.sql). P80 drives promise dates; P50 is shown for context.
-- A lane/stage pair needs at least 5 observations before it is published at all.
select
    origin_port,
    dest_fc_code,
    from_stage,
    to_stage,
    cast(percentile_cont(0.5) within group (order by days) as numeric(6, 2)) as p50_days,
    cast(percentile_cont(0.8) within group (order by days) as numeric(6, 2)) as p80_days,
    cast(count(*) as integer)                                                 as sample_n,
    now()                                                                     as computed_at
from {{ ref('int_lane_transits') }}
group by origin_port, dest_fc_code, from_stage, to_stage
having count(*) >= 5
