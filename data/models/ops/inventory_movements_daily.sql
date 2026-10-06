-- The stock ledger per business day, FC, SKU and kind of movement (RECEIPT, RESERVE, SHIP, ...).
select
    {{ local_date('m.occurred_at') }}  as day,
    fc.code                            as fc_code,
    s.code                             as sku_code,
    m.kind,
    count(*)                           as movements,
    sum(m.on_hand_delta)               as on_hand_delta,
    sum(m.reserved_delta)              as reserved_delta
from {{ source('inbound', 'inventory_movement') }} m
join {{ source('inbound', 'sku') }} s                 on s.id = m.sku_id
join {{ source('inbound', 'fulfillment_center') }} fc on fc.id = m.fc_id
group by 1, 2, 3, 4
