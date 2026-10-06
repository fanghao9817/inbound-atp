{{ config(enabled=target.type == 'postgres') }}
-- The stock ledger is complete: per position, the movements add up to the position exactly.
with ledger as (
    select sku_id, fc_id, sum(on_hand_delta) as on_hand, sum(reserved_delta) as reserved
    from {{ source('inbound', 'inventory_movement') }}
    group by 1, 2
)
select p.sku_id, p.fc_id, p.on_hand, l.on_hand as ledger_on_hand, p.reserved, l.reserved as ledger_reserved
from {{ source('inbound', 'inventory_position') }} p
left join ledger l on l.sku_id = p.sku_id and l.fc_id = p.fc_id
where coalesce(l.on_hand, 0) <> p.on_hand or coalesce(l.reserved, 0) <> p.reserved
