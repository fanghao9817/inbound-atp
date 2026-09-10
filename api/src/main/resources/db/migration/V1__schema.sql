-- System of record for the ATP + inbound ETA service. Owned by the API (Flyway).
-- The analytics schema is a contract with the dbt project: the API only reads from it.

create table sku (
    id          bigserial primary key,
    code        text not null unique,
    name        text not null,
    category    text not null
);

create table fulfillment_center (
    id                    bigserial primary key,
    code                  text not null unique,
    name                  text not null,
    region                text not null,             -- e.g. CA-WEST, US-EAST
    receiving_buffer_days int  not null default 2    -- dock-to-stock time once a container arrives
);

-- Current stock per SKU per fulfillment center. reserved = already allocated to customer orders.
create table inventory_position (
    sku_id     bigint not null references sku (id),
    fc_id      bigint not null references fulfillment_center (id),
    on_hand    int    not null default 0 check (on_hand >= 0),
    reserved   int    not null default 0 check (reserved >= 0),
    version    int    not null default 0,             -- optimistic locking
    updated_at timestamptz not null default now(),
    primary key (sku_id, fc_id)
);

-- Future demand that is already committed (B2B orders, allocations, promises) and must be
-- honoured before new promises are made.
create table demand_commitment (
    id        bigserial primary key,
    sku_id    bigint not null references sku (id),
    fc_id     bigint not null references fulfillment_center (id),
    qty       int    not null check (qty > 0),
    need_by   date   not null,
    reference text   not null
);
create index demand_commitment_sku_fc_idx on demand_commitment (sku_id, fc_id, need_by);

create table purchase_order (
    id              bigserial primary key,
    po_number       text   not null unique,
    supplier        text   not null,
    origin_port     text   not null,                  -- UN/LOCODE, e.g. VNSGN, CNSHA
    dest_fc_id      bigint not null references fulfillment_center (id),
    status          text   not null check (status in ('OPEN', 'RECEIVED', 'CANCELLED')),
    planned_arrival date   not null,
    created_at      timestamptz not null default now()
);
create index purchase_order_status_idx on purchase_order (status, dest_fc_id);

create table purchase_order_line (
    id           bigserial primary key,
    po_id        bigint not null references purchase_order (id),
    sku_id       bigint not null references sku (id),
    qty_ordered  int    not null check (qty_ordered > 0),
    qty_received int    not null default 0 check (qty_received >= 0),
    unique (po_id, sku_id)
);
create index purchase_order_line_sku_idx on purchase_order_line (sku_id);

-- One container per PO keeps the demo simple; the model tolerates several.
create table shipment (
    id                   bigserial primary key,
    po_id                bigint not null references purchase_order (id),
    carrier              text   not null,
    container_no         text,
    planned_departure    date   not null,
    planned_arrival      date   not null,             -- carrier's original ETA at the FC
    predicted_arrival    date,                        -- our prediction, refreshed on every milestone
    predicted_confidence text   check (predicted_confidence in ('LOW', 'MEDIUM', 'HIGH')),
    prediction_basis     text,                        -- human-readable explanation of the prediction
    current_stage        text   not null default 'BOOKED',
    version              int    not null default 0,
    updated_at           timestamptz not null default now()
);
create index shipment_po_idx on shipment (po_id);

-- Immutable event log of what the carrier / customs / FC told us. event_id is the
-- idempotency key: the same EDI message replayed twice must not create two rows.
create table shipment_milestone (
    id          bigserial primary key,
    shipment_id bigint not null references shipment (id),
    type        text   not null check (type in ('BOOKED', 'DEPARTED_ORIGIN', 'ARRIVED_DEST_PORT', 'CUSTOMS_CLEARED', 'RECEIVED_FC')),
    occurred_at timestamptz not null,
    source      text   not null,
    event_id    uuid   not null unique,
    recorded_at timestamptz not null default now(),
    unique (shipment_id, type)
);

-- Contract table populated by dbt (model analytics.lane_lead_time_stats, materialized as table).
-- Kept here so the API starts with a well-defined (empty) shape before dbt has ever run.
create schema if not exists analytics;
create table analytics.lane_lead_time_stats (
    origin_port  text not null,
    dest_fc_code text not null,
    from_stage   text not null,
    to_stage     text not null,
    p50_days     numeric(6, 2) not null,
    p80_days     numeric(6, 2) not null,
    sample_n     int  not null,
    computed_at  timestamptz not null default now(),
    primary key (origin_port, dest_fc_code, from_stage, to_stage)
);
