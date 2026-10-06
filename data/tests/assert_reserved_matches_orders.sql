{{ config(enabled=target.type == 'postgres') }}
-- Every reserved unit belongs to a RESERVED order, and every RESERVED order holds its units.
with orders as (
    select sku_id, fc_id, sum(qty) as reserved
    from {{ source('inbound', 'customer_order') }}
    where status = 'RESERVED'
    group by 1, 2
)
select p.sku_id, p.fc_id, p.reserved, coalesce(o.reserved, 0) as reserved_by_orders
from {{ source('inbound', 'inventory_position') }} p
left join orders o on o.sku_id = p.sku_id and o.fc_id = p.fc_id
where p.reserved <> coalesce(o.reserved, 0)
