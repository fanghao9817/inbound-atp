-- How good were the arrival predictions, measured the honest way: for each container that has reached
-- the FC, take the prediction that was in force 14 days before it actually arrived (what a planner
-- would have promised from) and compare it with the real gate-in date. Predictions are P80, so about
-- 80% should be on time or early; much higher means promises are too cautious, lower means too bold.
with gate_in as (
    select m.shipment_id, {{ local_date('m.occurred_at') }} as arrived_day, m.occurred_at as arrived_at
    from {{ source('inbound', 'shipment_milestone') }} m
    where m.type = 'RECEIVED_FC'
),
in_force as (
    select g.shipment_id, g.arrived_day, l.predicted_arrival, l.stage as predicted_from_stage, l.confidence,
           l.reason, l.computed_at,
           row_number() over (partition by g.shipment_id order by l.computed_at desc, l.id desc) as rn
    from gate_in g
    join {{ source('inbound', 'eta_prediction_log') }} l
      on l.shipment_id = g.shipment_id
     and l.computed_at <= g.arrived_at - interval '14 day'
     and l.reason <> 'MIGRATION'
)
select
    f.shipment_id,
    po.po_number,
    po.origin_port,
    fc.code                                        as dest_fc_code,
    f.arrived_day,
    f.predicted_arrival,
    f.predicted_from_stage,
    f.confidence,
    f.arrived_day - f.predicted_arrival            as error_days,      -- positive = later than predicted
    f.arrived_day <= f.predicted_arrival           as on_time
from in_force f
join {{ source('inbound', 'shipment') }} s            on s.id = f.shipment_id
join {{ source('inbound', 'purchase_order') }} po     on po.id = s.po_id
join {{ source('inbound', 'fulfillment_center') }} fc on fc.id = po.dest_fc_id
where f.rn = 1
