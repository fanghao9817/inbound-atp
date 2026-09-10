-- For every container that has been received, the elapsed days from each earlier milestone to receipt.
-- This is the training data for arrival prediction: "from stage X on lane L, how long until the FC has it?"
with received as (
    select shipment_id, occurred_at as received_at
    from {{ ref('stg_milestones') }}
    where stage = 'RECEIVED_FC'
)
select
    m.shipment_id,
    m.origin_port,
    m.dest_fc_code,
    m.stage                                                        as from_stage,
    'RECEIVED_FC'                                                  as to_stage,
    extract(epoch from (r.received_at - m.occurred_at)) / 86400.0  as days
from {{ ref('stg_milestones') }} m
join received r on r.shipment_id = m.shipment_id
where m.stage <> 'RECEIVED_FC'
  and r.received_at > m.occurred_at
