-- Sanity: an 80th percentile can never be below the median.
select *
from {{ ref('lane_lead_time_stats') }}
where p80_days < p50_days
