-- V3: what an order's decision was when it was placed, and a per-position demand forecast.

-- 1) customer_order.status moves on (RESERVED -> SHIPPED, BACKORDERED -> RESERVED, ...). The activity feed
--    and "served on time" measures need the decision taken at placement, so it is kept separately.
alter table customer_order add column placed_status text;
update customer_order set placed_status = case
        when reserved_at is not null and reserved_at < created_at + interval '1 minute' then 'RESERVED'
        when status = 'REJECTED' then 'REJECTED'
        when need_by is not null and first_promise_date <= need_by then 'SCHEDULED'
        else 'BACKORDERED'
    end;
alter table customer_order alter column placed_status set not null;
alter table customer_order add constraint customer_order_placed_status_check
    check (placed_status in ('RESERVED', 'SCHEDULED', 'BACKORDERED', 'REJECTED'));

-- 2) Weekly demand forecast per SKU x FC, fitted on last year's sales. Replenishment blends it with
--    observed demand while live history is shorter than four weeks (the cold start), instead of
--    assuming the same rate for a best-seller and a slow mover.
create table demand_forecast (
    sku_id       bigint        not null references sku (id),
    fc_id        bigint        not null references fulfillment_center (id),
    weekly_units numeric(8, 2) not null check (weekly_units >= 0),
    method       text          not null,
    computed_at  timestamptz   not null default now(),
    primary key (sku_id, fc_id)
);
