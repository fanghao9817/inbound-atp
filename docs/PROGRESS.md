# Progress log (loop state)

Deploy target: Lightsail 34.208.44.222 (ubuntu, key in 申请/Article申请/). Code lives locally in WSL at /home/fangh/workspace/inbound-atp and is rsync'd to /home/ubuntu/inbound-atp on the box. Java builds/tests run on the box (local WSL only has Java 8).

## Plan (vertical slice, no AWS managed services yet)
1. [x] Skeleton + compose (postgres, kafka) up on box
2. [x] API: Spring Boot 4.1 / Java 21 — schema (Flyway), seed, ATP + fulfillment (raw JDBC) endpoints, 19 tests green
3. [x] Kafka: milestone ingest → event → ETA recalculation → eta-updated (covered by integration test)
4. [x] Web: Vue 3 + TS dashboard (availability, inbound POs, exceptions, lanes), nginx serves + proxies /api
5. [x] HTTPS on demo.haoyufang.dev (Cloudflare DNS-only A record; Let's Encrypt via scripts/issue-cert.sh)
6. [x] dbt: lane lead-time stats (p50/p80) from historical milestones → API reads (48 lanes/stages, 12 checks pass)
7. [x] GitHub Actions: ci.yml (mvn verify, web build+lint, dbt build), deploy.yml (rsync + roll-out) — unrun until the repo is on GitHub
8. [x] README + docs/HANDOFF.md

## Log
- 2026-09-10 night: API implemented and verified on the box (`mvn verify`: 19 tests incl. Testcontainers Postgres+Kafka).
  Interview question from April (inventory + purchase_orders by SKU, raw JDBC) recreated as the `fulfillment` module
  (`FulfillmentJdbcDao` on plain java.sql, repeatable-read snapshot; `AllocationPlanner` pure rule).
  Boot 4 gotchas: use `spring-boot-starter-kafka` (not bare spring-kafka) or the Testcontainers `@ServiceConnection`
  factory for Kafka is missing; Jackson 3 lives in `tools.jackson`; Testcontainers 2.x classes are in
  `org.testcontainers.postgresql` / `org.testcontainers.kafka`.
- 2026-09-10 ~02:30 PT: web, dbt, HTTPS, CI files done; see docs/HANDOFF.md. Loop stopped at the agreed scope (no AWS managed services).
- 2026-09-10 12:20 PT: domain live at https://demo.haoyufang.dev/. SSH outage root cause: internet SSH scanners filling sshd's unauthenticated slots; fail2ban (sshd jail, 4 tries/10m → 1h ban) installed.
