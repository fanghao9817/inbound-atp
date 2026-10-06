# inbound-atp

**Available-to-promise (ATP) with inbound arrival prediction** for a retailer that ships bulky goods from Asian
suppliers into several North-American fulfillment centers.

Live demo: https://demo.haoyufang.dev/ · API: `https://demo.haoyufang.dev/api/...` · health: `/health`

> **All data is synthetic** — invented SKUs, suppliers, carriers, ports and customers, driven by a simulator.
> It is not Article data.

The question it answers is the one every storefront and every planner asks: *"If a customer wants 5 of this
sofa in Calgary, when can we honestly promise it?"* — counting stock on hand, the containers that are still on
the water (at the date we *predict* they land, not the date the carrier promised), and the demand that is already
committed to someone else.

The demo is not a frozen snapshot. A **simulator** plays the outside world around the clock — carriers and customs
reporting milestones, the warehouse receiving and shipping, online and B2B customers ordering, a buyer placing
replenishment orders every Monday — through the same HTTP API any other system would use. Every day and every
week look different: evening and Sunday peaks, Monday's shipping backlog, the occasional congested port, promise
dates that move when a container slips.

## What is in here

| Part | Stack | What it does |
|---|---|---|
| `api/` | Java 21, Spring Boot 4.1, plain JDBC (`JdbcClient` + `java.sql`), Flyway, PostgreSQL 16, Kafka 4 (KRaft) | Orders and reservations, time-phased ATP, goods receipts with a stock ledger, milestone ingestion → Kafka → ETA prediction, replenishment proposals, dashboard |
| `simulator/` | Java 21, Spring Boot (no web server), `RestClient` | Carriers, customs, warehouse, customers and buyer; stateless and deterministic from a secret seed |
| `web/` | Vue 3.5, TypeScript, Vite 8, Pinia, Vue Router | Today (live KPIs and activity), availability with the full derivation, orders (try one), inbound, exceptions, lanes |
| `data/` | dbt Core 1.12 (dbt-postgres, dbt-databricks), SQL | Lane lead-time percentiles the API predicts from; orders, stock ledger and prediction-accuracy models; data-contract tests |
| `infra/` | Docker Compose, nginx, Let's Encrypt, systemd timers | One box: Postgres, Kafka, API, simulator; nginx serves the SPA and the public API; nightly and weekly jobs |
| `infra/aws/` | CloudFormation / SAM, Lambda (Python 3.12, arm64), DynamoDB | Storefront projection: `availability.changed` → projector Lambda → DynamoDB (conditional writes); read Lambda behind a Function URL; GitHub OIDC deploy role |
| `.github/workflows/` | GitHub Actions | `ci`: tests for every part; `deploy`: SAM via OIDC, then roll-out of the exact commit CI tested; `watchdog`: hourly check that the demo is up and its data is moving |

## How the pieces fit

```
simulator ── HTTP + token ──▶ /api/internal/**      (nginx answers 404 for these from the internet)
  carriers/customs   POST shipments/{id}/milestones ─▶ ShipmentService ─┐
  warehouse          POST shipments/{id}/receipt     ─▶ ReceivingService ─┤  one transaction each:
  customers          POST orders                     ─▶ OrderService     ─┤  state change + ledger row
  warehouse          POST orders/{id}/ship|cancel    ─▶ OrderService     ─┤  + outbox_event row
  buyer (Mondays)    POST purchase-orders            ─▶ PurchaseOrderService
                                                                          │
                                         OutboxRelay (polls, in id order) ▼
                                                   Kafka  shipment.milestones ──▶ ETA recalculation ──▶ eta_prediction_log
                                                   Kafka  shipment.eta-updated
                                                   Kafka  availability.changed ──▶ projector Lambda ──▶ DynamoDB ◀── storefront read Lambda

GET /api/atp?sku&fc&qty ──▶ AtpService ──▶ AtpCalculator (pure): projected stock over time,
                                             ATP = forward-looking minimum, promise date = first day ATP ≥ qty
POST /api/fulfillment/quote ──▶ FulfillmentJdbcDao (java.sql, one repeatable-read snapshot) ──▶ AllocationPlanner (pure)

dbt (nightly): milestones ──▶ int_lane_transits (last 365 days) ──▶ analytics.lane_lead_time_stats ──▶ EtaPredictor
               orders, ledger, prediction log ──▶ orders_daily, inventory_movements_daily, eta_accuracy
```

Design decisions worth asking about:

- **SQL first, no ORM.** Repositories are `JdbcClient` with explicit SQL; the fulfillment read path is written
  against `java.sql` so it is obvious which statements run in which transaction (both reads share one
  repeatable-read snapshot, so a receipt landing between them cannot be counted twice).
- **An order is decided under a row lock.** `OrderService` locks the SKU × FC position (`SELECT … FOR UPDATE`),
  computes ATP inside the same transaction and then reserves stock, backorders against an inbound container, or
  rejects. A test fires 20 concurrent orders at 5 units of stock and gets exactly 5 reservations.
- **Stock has a ledger.** Every change to a position writes one `inventory_movement` row in the same transaction;
  a dbt test checks that the ledger sums to the positions, and a constraint keeps `reserved ≤ on_hand`.
- **Gate-in is not stock.** `RECEIVED_FC` means the container is at the dock; stock becomes sellable when the
  warehouse posts the goods receipt (idempotent on its event id), the same dock-to-stock days ATP promises with.
- **ATP takes the look-ahead minimum.** Stock that looks free on day 10 may be spoken for by a commitment due on
  day 20; the naive projection over-promises. `AtpCalculator` is a pure function with table-driven unit tests.
- **Promise on P80, not on the mean.** Confidence grows with sample size and with how far along the container is.
  A container past its predicted date drops to LOW confidence and is re-predicted from today, not left stale.
- **Accuracy is measured honestly.** Every prediction is logged; a container is scored against the prediction that
  was in force 14 days before it actually arrived (`eta_accuracy`, and the P80 tile on the Today page).
- **Transactional outbox.** Events are written in the same transaction as the change and relayed to Kafka in
  order; consumers recompute from the database, so a duplicate or replayed event is harmless. Poison messages go
  to a dead-letter topic after retries with back-off.
- **The storefront never reads the operational database.** Availability is projected into DynamoDB by a Lambda;
  writes are conditional on a numeric `updatedAtMs`, so replays and out-of-order invocations cannot regress a row.
- **Writes are internal.** Everything that changes the world is under `/api/internal/**`, needs a token, and does
  not exist from the internet. The public API is reads, a what-if ETA preview that records nothing, and a capped
  visitor order form (≤ 5 units, left out of the KPIs, cancelled after an hour).
- **No long-lived cloud keys in CI.** GitHub Actions assumes a deploy role through OIDC; deploys run only after a
  green CI on a push to `main`, check out exactly that commit, and connect to the box with pinned host keys.

## The simulator

`simulator/` is a separate Spring Boot process with no database access and no state. Every "random" event is a
hash of a secret seed and a key such as (purchase order, stage), so after a restart it recomputes exactly the
same truth, and replays hit the API's idempotency keys. Calibration, with the reasoning, is in
[`simulator/README.md`](simulator/README.md); a 26-week dry run in its tests checks that the replenishment policy
keeps the network healthy (fill rate 85–99%, no runaway stock) at the configured demand.

| Who | When | What |
|---|---|---|
| Online customers | Poisson per FC per hour; evenings ×1.8, Sundays ×1.2, Black Friday up to ×1.3 | Ask for a date first; buy if it is within 2 weeks, 70% of them if 2–5 weeks, 30% beyond; no date at all → lost demand (REJECTED) |
| B2B customers | about one per weekday morning | 5–20 units wanted 3–10 weeks out → SCHEDULED, reserved 2 days before |
| Carriers, customs | every 5 minutes | Each container's next milestone at its true time, from the lane's transit distribution; 10% stuck at port; about one week in twelve a gateway is congested |
| Warehouse | Mon–Sat 07:00–19:00 local | Gate-in in dock hours; goods receipt after the FC's dock-to-stock days (≈1% damaged, occasional short shipment); ships reserved orders after 2 h of picking |
| Cancellations | hourly | 1% of new reservations in their first day; 0.5% of backorders per day |
| Buyer | Mondays 08:00 Vancouver | Places the API's replenishment proposals as purchase orders |

## Running it

```bash
# infrastructure
cd infra && cp .env.example .env && docker compose up -d postgres kafka

# api (needs JDK 21 + Maven; tests use Testcontainers, so Docker must be reachable)
cd api && mvn verify && mvn spring-boot:run          # seeds a year of demo history on an empty database

# simulator (needs the API and INTERNAL_API_TOKEN set in both)
cd simulator && mvn verify && mvn spring-boot:run

# web
cd web && npm ci && npm run dev                       # proxies /api to the deployed API (see vite.config.ts)

# data
cd data && python3 -m venv .venv && . .venv/bin/activate && pip install "dbt-postgres<2"
DBT_PG_PASSWORD=... dbt build --profiles-dir .
```

A throwaway copy of the whole stack, next to the live one: `infra/compose.dev.yaml`.

On the box (`scripts/`): `deploy-api.sh` (backup, build, roll out, wait for healthy), `reset-demo.sh --yes`
(backup, wipe, re-seed, dbt, hand over to the simulator from now), `nightly.sh` and `weekly-databricks.sh`
(run by the systemd timers in `infra/systemd`, installed by `install-timers.sh`), `backup-db.sh`.

## API

Public (through nginx):

| Endpoint | Purpose |
|---|---|
| `GET /api/atp?sku=&fc=&qty=` · `GET /api/atp/{sku}?qty=` | Promise date with the timeline, supplies and demands behind it; one line per FC |
| `POST /api/fulfillment/quote` | Inventory first, then purchase orders in arrival order (plain-JDBC path) |
| `GET /api/orders` · `POST /api/orders` | Recent orders; a visitor order (≤ 5 units) |
| `GET /api/purchase-orders` · `GET /api/shipments/{id}` | Inbound book; container detail with its milestones |
| `POST /api/shipments/{id}/eta-preview` | What the prediction would become if a milestone happened then (records nothing) |
| `GET /api/exceptions` · `GET /api/exceptions/shortages` | Late and overdue containers; positions where commitments outrun supply |
| `GET /api/dashboard/kpis` · `/daily` · `GET /api/activity` | Today page |
| `GET /api/lanes/stats` · `GET /api/refresh/last` · `GET /api/planning/replenishment` | dbt output; last re-score; (R,S) proposals |
| `GET <function-url>/?sku=CODE[&fc=CODE]` | Storefront read over the DynamoDB projection (Lambda, no database access) |

Internal (`X-Internal-Token`, box network only): `POST /api/internal/orders`, `…/orders/{id}/ship|cancel`,
`…/shipments/{id}/milestones`, `…/shipments/{id}/receipt`, `…/purchase-orders`, `…/notes`,
`…/eta/recalculate-all`, `…/availability/project-all`, `…/allocation/run`.

## Where each part of the job description is exercised

| Requirement | Where |
|---|---|
| Java backend services, REST APIs | `api/` — Spring Boot 4.1, `JdbcClient` + plain `java.sql`, Flyway |
| Vue.js + TypeScript | `web/` — Vue 3.5 + TS |
| Event-driven workflows, Kafka | Transactional outbox → `shipment.milestones`, `shipment.eta-updated`, `availability.changed`; idempotent consumers; dead-letter topics |
| Relational + NoSQL data models | PostgreSQL schema in `api/src/main/resources/db/migration`; DynamoDB single-table projection in `infra/aws/template.yaml` |
| AWS, Lambda, serverless, CloudFormation | `infra/aws/` SAM stack, deployed by GitHub OIDC; Lambda Function URL read path |
| Python, SQL, dbt, Databricks | `data/` dbt project on PostgreSQL (nightly) and Databricks Free Edition (weekly snapshot); Lambdas in Python |
| PostgreSQL | PostgreSQL 16; the fulfillment read path is plain ANSI-style SQL (not tested on MySQL) |
| CI/CD, automated tests, monitoring | GitHub Actions `ci`, `deploy`, `watchdog`; JUnit + Testcontainers, simulator calibration test, Vitest, dbt tests; Actuator health and Prometheus metrics |

## Status and known limitations

- The world is simulated. Transit times, demand and the warehouse follow the distributions in
  `simulator/README.md`; they are plausible, not fitted to real data.
- One container per purchase order, one supplier per SKU, no transfers between FCs, no partial shipments of an order.
- Everything runs on one Lightsail box (backups nightly, kept 14 days, on the same box); the storefront
  projection and its read path are on AWS. Cost: the box ($44/month) is almost everything; DynamoDB, Lambda and
  CloudFormation stay in the AWS free tier, Databricks runs on the Free Edition.
- The P80 accuracy tile needs a few weeks of live history after a reset before it has anything to score.
- Business time is Vancouver. tzdata 2026b keeps British Columbia on UTC-7 all year from November 2026; the
  API, PostgreSQL and the browser format all follow the installed tz database.
