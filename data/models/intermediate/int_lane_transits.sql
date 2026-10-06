-- For every container received in the last 365 days, the elapsed days from each earlier milestone to
-- receipt. This is the training data for arrival prediction: "from stage X on lane L, how long until
-- the FC has it?" The window makes the statistics follow the lanes as they change (congestion, a slow
-- season) instead of averaging over all time. Containers whose timeline contains a recovery event
-- (reported late, at an approximate time, after a feed outage) are left out.
with received as (
    select shipment_id, occurred_at as received_at
    from {{ ref('stg_milestones') }}
    where stage = 'RECEIVED_FC'
      and occurred_at >= current_timestamp - interval '365' day
),
recovered as (
    select distinct shipment_id
    from {{ ref('stg_milestones') }}
    where source = 'CARRIER_EDI_RECOVERY'
)
select
    m.shipment_id,
    m.origin_port,
    m.dest_fc_code,
    m.stage                                                        as from_stage,
    'RECEIVED_FC'                                                  as to_stage,
    {{ days_between('r.received_at', 'm.occurred_at') }}          as days
from {{ ref('stg_milestones') }} m
join received r on r.shipment_id = m.shipment_id
where m.stage <> 'RECEIVED_FC'
  and r.received_at > m.occurred_at
  and m.shipment_id not in (select shipment_id from recovered)
