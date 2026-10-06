-- V2: the demand side (orders), goods receipts, an append-only stock ledger, a prediction log for
-- accuracy, a transactional outbox, and clean-ups of the seeded data. Existing rows are migrated so
-- that one flow handles all demand:
--   * every demand_commitment becomes an order (SCHEDULED if it is not due yet, else BACKORDERED),
--     and demand_commitment itself becomes a view over those orders - one source of truth, nothing to sync
--   * every reserved quantity becomes a RESERVED order waiting to be picked
--   * every inventory position gets an OPENING ledger row, so the ledger sums to the position

-- 0) Business day = Vancouver. Timestamps are stored as instants either way; this only changes what
--    current_date and casts to date mean in this database.
do $$ begin execute format('alter database %I set timezone to %L', current_database(), 'America/Vancouver'); end $$;
set timezone to 'America/Vancouver';

-- 1) Seeded history contained milestones dated after they were recorded (up to 20 days). Move each
--    affected shipment's whole timeline back by whole days so nothing happened in the future; the
--    transit durations, and therefore the lane statistics, are unchanged.
with ahead as (
    select shipment_id, date_trunc('day', max(occurred_at - recorded_at)) + interval '1 day' as shift
    from shipment_milestone
    group by shipment_id
    having max(occurred_at - recorded_at) > interval '1 hour'
)
update shipment_milestone m set occurred_at = m.occurred_at - a.shift
from ahead a where m.shipment_id = a.shipment_id;

-- 2) Milestone hygiene: a fixed vocabulary of sources, and one container per PO (every supply query
--    joins PO -> shipment and would double count otherwise)
alter table shipment_milestone add constraint shipment_milestone_source_check
    check (source in ('CARRIER_EDI', 'CARRIER_EDI_RECOVERY', 'CUSTOMS_BROKER', 'WMS', 'BUYER', 'MANUAL'));
create unique index shipment_po_uq on shipment (po_id);
create index shipment_milestone_recorded_idx on shipment_milestone (recorded_at);

-- 3) Customer orders -----------------------------------------------------------------------------
--    RESERVED     stock on hand set aside (inventory_position.reserved), waiting to be picked
--    SCHEDULED    future-dated (B2B) demand that ATP covers by its date; no stock locked yet
--    BACKORDERED  promised against inbound stock because nothing is free today
--    SHIPPED / CANCELLED final;  REJECTED no date could be promised within the horizon (lost demand)
create table customer_order (
    id           bigserial primary key,
    order_ref    text   not null unique,                 -- caller-supplied idempotency key
    channel      text   not null check (channel in ('ONLINE', 'B2B', 'STORE')),
    sku_id       bigint not null references sku (id),
    fc_id        bigint not null references fulfillment_center (id),
    qty          int    not null check (qty > 0),
    status       text   not null check (status in ('RESERVED', 'SCHEDULED', 'BACKORDERED', 'SHIPPED', 'CANCELLED', 'REJECTED')),
    promise_date date,                                   -- current promise; moves later if inbound stock slips
    first_promise_date date,                             -- the date first promised, kept to measure slippage
    need_by      date,                                   -- requested date (B2B); null = as soon as possible
    origin       text   not null default 'FEED'
                 check (origin in ('FEED', 'VISITOR', 'SEED', 'MIGRATED')),  -- flow KPIs count FEED only
    created_at   timestamptz not null default now(),
    reserved_at  timestamptz,
    shipped_at   timestamptz,
    cancelled_at timestamptz,
    version      int not null default 0
);
create index customer_order_position_status_idx on customer_order (sku_id, fc_id, status);
create index customer_order_status_idx on customer_order (status, reserved_at);
create index customer_order_created_idx on customer_order (created_at);
create index customer_order_shipped_idx on customer_order (shipped_at) where shipped_at is not null;

insert into customer_order (order_ref, channel, sku_id, fc_id, qty, status, promise_date, first_promise_date, need_by, origin, created_at)
select case when count(*) over (partition by dc.reference) > 1 then dc.reference || '-' || dc.id else dc.reference end,
       case when dc.reference like 'STORE-%' then 'STORE' else 'B2B' end,
       dc.sku_id, dc.fc_id, dc.qty,
       case when dc.need_by > current_date + 2 then 'SCHEDULED' else 'BACKORDERED' end,
       dc.need_by, dc.need_by, dc.need_by, 'MIGRATED', now()
from demand_commitment dc;

insert into customer_order (order_ref, channel, sku_id, fc_id, qty, status, promise_date, first_promise_date, origin, created_at, reserved_at)
select 'LEGACY-RES-' || ip.sku_id || '-' || ip.fc_id, 'ONLINE', ip.sku_id, ip.fc_id, ip.reserved, 'RESERVED',
       (ip.updated_at at time zone 'America/Vancouver')::date, (ip.updated_at at time zone 'America/Vancouver')::date,
       'MIGRATED', ip.updated_at, ip.updated_at
from inventory_position ip
where ip.reserved > 0;

-- the commitments now live on the orders; keep the old name as a read-only view so every ATP query
-- (and the plain-JDBC fulfillment path) reads exactly what it read before
drop table demand_commitment;
create view demand_commitment as
select o.id, o.sku_id, o.fc_id, o.qty, o.promise_date as need_by, o.order_ref as reference, o.id as order_id
from customer_order o
where o.status in ('SCHEDULED', 'BACKORDERED');

-- 4) Append-only stock ledger ----------------------------------------------------------------------
-- Every change to inventory_position writes exactly one row here in the same transaction, so the sum
-- of the deltas per position always equals the position (checked by a test and a dbt test).
create table inventory_movement (
    id             bigserial primary key,
    sku_id         bigint not null references sku (id),
    fc_id          bigint not null references fulfillment_center (id),
    kind           text   not null check (kind in ('OPENING', 'RECEIPT', 'RESERVE', 'RELEASE', 'SHIP', 'ADJUST')),
    on_hand_delta  int    not null,
    reserved_delta int    not null,
    ref_type       text,                                 -- ORDER / RECEIPT / OPENING
    ref_id         bigint,
    occurred_at    timestamptz not null default now()
);
create index inventory_movement_time_idx on inventory_movement (occurred_at);
create index inventory_movement_position_idx on inventory_movement (sku_id, fc_id, occurred_at);

insert into inventory_movement (sku_id, fc_id, kind, on_hand_delta, reserved_delta, ref_type, occurred_at)
select sku_id, fc_id, 'OPENING', on_hand, reserved, 'OPENING', updated_at
from inventory_position;

-- the conditional updates in the API keep this true; the constraint is defence in depth
alter table inventory_position add constraint inventory_position_reserved_within_on_hand check (reserved <= on_hand);
comment on column inventory_position.version is
    'change counter (incremented on every movement); concurrency is handled by SELECT ... FOR UPDATE on the row, not optimistic locking';

-- sourcing rule: where each SKU is bought (one supplier per SKU in this demo), used by replenishment
create table sku_source (
    sku_id      bigint primary key references sku (id),
    origin_port text   not null,
    supplier    text   not null
);
insert into sku_source (sku_id, origin_port, supplier)
select id,
       case when category = 'Sofas' then 'VNSGN' when category in ('Tables', 'Bedroom') then 'MYPKG' else 'CNSHA' end,
       case when category = 'Sofas' then 'Saigon Furniture Co.' when category in ('Tables', 'Bedroom') then 'Klang Valley Timber'
            else 'Shanghai Home Works' end
from sku;

-- 5) Goods receipts ------------------------------------------------------------------------------------
-- RECEIVED_FC is the container at the dock (gate-in). Stock becomes sellable only when the warehouse
-- posts the putaway, receiving_buffer_days later - the same dock-to-stock time ATP promises with.
create table goods_receipt (
    id             bigserial primary key,
    shipment_id    bigint not null unique references shipment (id),   -- one container, one receipt
    event_id       uuid   not null unique,                            -- idempotency key from the WMS
    received_at    timestamptz not null,
    units_received int    not null check (units_received >= 0),
    units_damaged  int    not null check (units_damaged >= 0),
    recorded_at    timestamptz not null default now()
);
alter table purchase_order_line add column qty_damaged int not null default 0 check (qty_damaged >= 0);

-- replenishment POs are created through the API; client_ref makes that call idempotent
alter table purchase_order add column client_ref text unique;
create sequence purchase_order_number_seq start with 2000;
create index purchase_order_created_idx on purchase_order (created_at);

-- 6) Every prediction we made, so accuracy can be measured once the container is received ---------------
create table eta_prediction_log (
    id                bigserial primary key,
    shipment_id       bigint not null references shipment (id),
    stage             text   not null,                   -- latest milestone when the prediction was made
    reason            text   not null check (reason in ('MILESTONE', 'DAILY', 'STATS_REFRESH', 'SEED', 'MIGRATION')),
    predicted_arrival date   not null,
    confidence        text   not null,
    applied_days      int,                               -- whole days added to the milestone (null = fallback)
    sample_n          int,
    computed_at       timestamptz not null default now()
);
create index eta_prediction_log_shipment_idx on eta_prediction_log (shipment_id, computed_at);
create index eta_prediction_log_time_idx on eta_prediction_log (computed_at);

insert into eta_prediction_log (shipment_id, stage, reason, predicted_arrival, confidence, computed_at)
select id, current_stage, 'MIGRATION', predicted_arrival, coalesce(predicted_confidence, 'LOW'), updated_at
from shipment
where predicted_arrival is not null;

-- 7) Transactional outbox --------------------------------------------------------------------------------
-- Events are inserted in the same transaction as the state change; OutboxRelay publishes them to Kafka
-- in id order. A crash between commit and send can no longer lose one.
create table outbox_event (
    id           bigserial primary key,
    topic        text not null,
    msg_key      text not null,
    payload      text not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz,
    attempts     int  not null default 0,
    last_error   text
);
create index outbox_event_pending_idx on outbox_event (id) where published_at is null;

-- 8) Operations notes (e.g. port congestion announced by the carrier feed); ref makes the POST idempotent
create table ops_note (
    id         bigserial primary key,
    ref        text not null unique,
    kind       text not null,
    message    text not null,
    created_at timestamptz not null default now()
);
