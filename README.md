# inbound-atp

**Available-to-promise (ATP) with inbound arrival prediction** for a retailer that ships bulky goods from Asian
suppliers into several North-American fulfillment centers.

Live demo: https://demo.haoyufang.dev/ · API: `https://demo.haoyufang.dev/api/...` · health: `/health`

The question it answers is the one every storefront and every planner asks: *"If a customer wants 5 of this
sofa in Calgary, when can we honestly promise it?"* — counting stock on hand, the containers that are still on
the water (at the date we *predict* they land, not the date the carrier promised), and the demand that is already
committed to someone else.

## What is in here

| Part | Stack | What it does |
|---|---|---|
| `api/` | Java 21, Spring Boot 4.1, plain JDBC (`JdbcClient` + `java.sql`), Flyway, PostgreSQL 16, Kafka 4 (KRaft) | Time-phased ATP, fulfillment quotes, purchase-order/shipment read models, milestone ingestion → Kafka → ETA recalculation |
| `web/` | Vue 3.5, TypeScript, Vite 8, Pinia, Vue Router | Planner UI: availability by FC with the full derivation, inbound book, exceptions, lane statistics |
| `data/` | dbt Core 1.12 (dbt-postgres), SQL | Lane lead-time percentiles (P50/P80) from a year of milestone history, with schema + singular tests |
| `infra/` | Docker Compose, nginx, Let's Encrypt | One-box deployment: Postgres, Kafka, API containers; nginx serves the SPA and proxies `/api` over HTTPS |
| `infra/aws/` | CloudFormation / SAM, Lambda (Python 3.12, arm64), DynamoDB | Storefront projection: `shipment.eta-updated` → SDK invoke → projector Lambda → DynamoDB (conditional writes); read Lambda behind a Function URL; GitHub OIDC deploy role |
| `.github/workflows/` | GitHub Actions | `ci`: `mvn verify` with Testcontainers, `vue-tsc` + Vite build + lint, `dbt build` on a throwaway Postgres, `cfn-lint`; `deploy`: SAM stack via OIDC (no stored AWS keys), then rsync + roll-out + dbt on Postgres **and** Databricks |

## How the pieces fit

```
                 POST /api/shipments/{id}/milestones
carrier EDI ───▶ ShipmentService (tx: insert milestone if new, advance stage)
                     │ after commit
                     ▼
              Kafka  shipment.milestones ──▶ EtaRecalculationConsumer ──▶ EtaPredictor
                                                                          │  (P80 of lane history from the latest stage)
                                                                          ▼
                                                    shipment.predicted_arrival / confidence / basis
                                                                          │ if changed
                                                                          ▼
                                                             Kafka  shipment.eta-updated

GET /api/atp?sku&fc&qty ──▶ AtpService ──▶ AtpCalculator (pure): projected stock over time,
                                             ATP = forward-looking minimum, promise date = first day ATP ≥ qty
POST /api/fulfillment/quote ──▶ FulfillmentJdbcDao (java.sql, one repeatable-read snapshot) ──▶ AllocationPlanner (pure)

dbt: shipment_milestone ──▶ stg_milestones ──▶ int_lane_transits ──▶ analytics.lane_lead_time_stats ──▶ EtaPredictor
     (same models run on PostgreSQL from the live tables and on Databricks Free Edition from seeded snapshots)

Kafka  shipment.eta-updated ──▶ EtaUpdatedProjectionConsumer ──▶ AtpService (SKUs on that container × its FC)
                                                                     │  AWS SDK Invoke (async, invoke-only IAM user)
                                                                     ▼
                                          Lambda inbound-atp-projector ──▶ DynamoDB inbound-atp-availability
                                                                                     ▲
                       storefront  GET <function-url>/?sku=…  ◀── Lambda inbound-atp-availability-api ─┘
```

Design decisions worth asking about:

- **SQL first, no ORM.** Repositories are `JdbcClient` with explicit SQL; the fulfillment read path is deliberately
  written against `java.sql` so it is obvious which statements run and in which transaction (both reads share one
  repeatable-read snapshot, so a receipt landing between them cannot be counted twice).
- **ATP takes the look-ahead minimum.** Stock that looks free on day 10 may be spoken for by a commitment due on
  day 20; the naive projection over-promises. `AtpCalculator` is a pure function with exhaustive unit tests.
- **Promise on P80, not on the mean.** Under-promise and over-deliver; confidence grows with sample size and with
  how far along the container is. When a lane has no history the prediction falls back to the carrier plan and
  says so (`prediction_basis`).
- **Recalculation is idempotent by construction.** The consumer recomputes from database state, never from the
  event payload, so replays and out-of-order milestones converge. Milestones carry an `event_id` and the same
  message is a no-op the second time (`insertMilestoneIfNew`). Stage transitions only move forward.
- **Events are published after commit.** A consumer can never see an event whose row was rolled back. A full
  transactional outbox (surviving a crash between commit and send) is the documented next step.
- **dbt owns the analytics schema, the API only reads it.** `analytics.lane_lead_time_stats` is a contract table:
  Flyway creates its shape so the API starts cleanly before the first `dbt run`; dbt replaces the content.
- **The storefront never reads the operational database.** Availability is projected into DynamoDB (single table,
  `pk=SKU#…`, `sk=FC#…`) by a Lambda; writes are conditional on `updatedAt`, so replays and out-of-order
  invocations cannot regress a row. The box holds an IAM user that can do exactly one thing: invoke that Lambda.
- **No long-lived cloud keys in CI.** GitHub Actions assumes `inbound-atp-github-deploy` through OIDC; the role
  is scoped to the stack and to resources named `inbound-atp-*`.
- **Warehouse-portable SQL.** `src()` swaps live sources for seeded snapshots and `days_between()` picks the
  dialect, so one dbt project runs unchanged on PostgreSQL and Databricks.

## Running it

```bash
# infrastructure
cd infra && cp .env.example .env && docker compose up -d postgres kafka

# api (needs JDK 21 + Maven; tests use Testcontainers, so Docker must be reachable)
cd api && mvn verify && mvn spring-boot:run          # seeds demo data on an empty database

# web
cd web && npm ci && npm run dev                       # proxies /api to the deployed API (see vite.config.ts)

# data
cd data && python3 -m venv .venv && . .venv/bin/activate && pip install "dbt-postgres<2"
DBT_PG_PASSWORD=... dbt build --profiles-dir .
curl -X POST localhost:8080/api/eta/recalculate-all   # re-score open shipments with fresh statistics
```

Deployment scripts: `scripts/deploy-api.sh`, `scripts/deploy-web.sh`, `scripts/issue-cert.sh <domain>`,
`scripts/render-nginx.sh` (box) and `scripts/deploy-aws.sh [bootstrap]` (AWS, see `infra/aws/README.md`).
The seed is deterministic (`Random(42)`), relative to today's date.

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/atp?sku=&fc=&qty=` | Promise date with the full timeline, supplies and demands behind it |
| `GET /api/atp/{sku}?qty=` | One line per fulfillment center |
| `POST /api/fulfillment/quote` | Inventory-first, then purchase orders in arrival order (plain-JDBC path) |
| `GET /api/purchase-orders?status=OPEN` | Inbound book with shipment stage, planned vs predicted arrival |
| `GET /api/shipments/{id}` · `POST /api/shipments/{id}/milestones` | Container detail; milestone ingestion (202, async recalculation) |
| `GET /api/exceptions` | Containers predicted later than planned, with commitments falling due before arrival |
| `GET /api/lanes/stats` · `POST /api/eta/recalculate-all` | dbt output; re-score every open shipment (and re-project) |
| `POST /api/availability/project-all` · `GET /api/config` | Push every SKU × FC to DynamoDB; runtime config for the web app |
| `GET <function-url>/?sku=CODE[&fc=CODE]` | Storefront read over the DynamoDB projection (Lambda, no database access) |
| `GET /api/skus` · `GET /api/fulfillment-centers` · `/actuator/health` | Catalog and health |

## Where each part of the job description is exercised

| Requirement | Where |
|---|---|
| Java backend services, REST APIs | `api/` — Spring Boot 4.1, `JdbcClient` + plain `java.sql`, Flyway |
| Vue.js + TypeScript (React) | `web/` — Vue 3.5 + TS planner UI (React intentionally not bolted on) |
| Event-driven workflows, Kafka | `shipment.milestones` → ETA recalculation → `shipment.eta-updated` → storefront projection; idempotent consumers |
| Relational + NoSQL data models | PostgreSQL schema in `V1__schema.sql`; DynamoDB single-table projection in `infra/aws/template.yaml` |
| AWS, Lambda, serverless, CloudFormation | `infra/aws/` SAM stack, deployed by GitHub OIDC; Lambda Function URL read path |
| Python, SQL, dbt, Databricks | `data/` dbt project on PostgreSQL and Databricks Free Edition; Lambdas in Python |
| MySQL / PostgreSQL | PostgreSQL 16 in production; the fulfillment read path is ANSI SQL and runs unchanged on MySQL |
| CI/CD, automated tests, monitoring | GitHub Actions `ci` + `deploy`; 19 JVM tests incl. Testcontainers end-to-end; dbt tests; Actuator health/metrics |
| eCommerce / high-traffic customer-facing | The storefront read path is the DynamoDB projection, isolated from the operational database |

## Roadmap (next)

- Transactional outbox for event publishing.
- Kafka Connect / Debezium CDC from the operational tables instead of application-published events.
- Observability stack (Prometheus + Grafana) on the box; the API already exposes `/actuator/prometheus`.
