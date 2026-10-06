-- One row per customer order with codes instead of ids and business-day dates (Vancouver).
select
    o.id                                   as order_id,
    o.order_ref,
    o.channel,
    o.origin,
    s.code                                 as sku_code,
    s.category,
    fc.code                                as fc_code,
    o.qty,
    o.status,
    o.created_at,
    {{ local_date('o.created_at') }}       as created_day,
    o.reserved_at,
    o.shipped_at,
    {{ local_date('o.shipped_at') }}       as shipped_day,
    o.cancelled_at,
    o.need_by,
    o.first_promise_date,
    o.promise_date,
    o.promise_date - o.first_promise_date  as slipped_days,
    o.promise_date - {{ local_date('o.created_at') }} as promised_lead_days
from {{ source('inbound', 'customer_order') }} o
join {{ source('inbound', 'sku') }} s                 on s.id = o.sku_id
join {{ source('inbound', 'fulfillment_center') }} fc on fc.id = o.fc_id
