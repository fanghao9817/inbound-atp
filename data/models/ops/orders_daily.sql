-- Demand and its outcome per business day, FC and channel. Counts live demand only (origin FEED: the
-- simulated customers), not seeded or visitor orders. Outcomes use the decision taken when each order
-- was placed: reserved from stock, scheduled for the requested (B2B) date, backordered (promised later
-- than asked), or rejected (no date within the horizon). On time = reserved or scheduled.
with placed as (
    select created_day as day, fc_code, channel,
           count(*)                                                                as orders,
           sum(qty)                                                                as units_ordered,
           sum(case when placed_status = 'RESERVED' then qty else 0 end)           as units_from_stock,
           sum(case when placed_status = 'SCHEDULED' then qty else 0 end)          as units_scheduled,
           sum(case when placed_status = 'BACKORDERED' then qty else 0 end)        as units_backordered,
           sum(case when placed_status = 'REJECTED' then qty else 0 end)           as units_rejected,
           avg(case when placed_status <> 'REJECTED' then promised_lead_days end)  as avg_promised_lead_days
    from {{ ref('stg_orders') }}
    where origin = 'FEED'
    group by 1, 2, 3
),
shipped as (
    select shipped_day as day, fc_code, channel, sum(qty) as units_shipped
    from {{ ref('stg_orders') }}
    where origin = 'FEED' and shipped_day is not null
    group by 1, 2, 3
)
select
    coalesce(p.day, s.day)                   as day,
    coalesce(p.fc_code, s.fc_code)           as fc_code,
    coalesce(p.channel, s.channel)           as channel,
    coalesce(p.orders, 0)                    as orders,
    coalesce(p.units_ordered, 0)             as units_ordered,
    coalesce(p.units_from_stock, 0)          as units_from_stock,
    coalesce(p.units_scheduled, 0)           as units_scheduled,
    coalesce(p.units_backordered, 0)         as units_backordered,
    coalesce(p.units_rejected, 0)            as units_rejected,
    coalesce(s.units_shipped, 0)             as units_shipped,
    cast(p.avg_promised_lead_days as numeric(6, 1)) as avg_promised_lead_days
from placed p
full outer join shipped s on s.day = p.day and s.fc_code = p.fc_code and s.channel = p.channel
