-- The API looks stats up by (lane, from_stage, to_stage); a duplicate would make the lookup ambiguous.
select origin_port, dest_fc_code, from_stage, to_stage, count(*)
from {{ ref('lane_lead_time_stats') }}
group by 1, 2, 3, 4
having count(*) > 1
