{{ config(enabled=target.type == 'postgres') }}
-- Nothing is reported before it happened: a milestone may not be dated more than 15 minutes after it was recorded.
select id, shipment_id, type, occurred_at, recorded_at
from {{ source('inbound', 'shipment_milestone') }}
where occurred_at > recorded_at + interval '15 minutes'
