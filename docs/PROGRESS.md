# Progress log (loop state)

Deploy target: Lightsail 34.208.44.222 (ubuntu, key in 申请/Article申请/). Code lives locally in WSL at /home/fangh/workspace/inbound-atp and is rsync'd to /home/ubuntu/inbound-atp on the box. Java builds/tests run on the box (local WSL only has Java 8).

## Plan (vertical slice, no AWS managed services yet)
1. [ ] Skeleton + compose (postgres, kafka) up on box
2. [ ] API: Spring Boot 4.1 / Java 21 — schema (Flyway), seed, ATP endpoint, tests
3. [ ] Kafka: milestone ingest → event → ETA recalculation → eta-updated
4. [ ] Web: Vue 3 + TS dashboard (availability, inbound POs, exceptions), nginx serves + proxies /api
5. [ ] HTTPS via sslip.io (swap to real domain later)
6. [ ] dbt: lane lead-time stats (p50/p80) from historical milestones → API reads
7. [ ] GitHub Actions: ci.yml (mvn verify, web build, dbt parse), deploy.yml (rsync + compose)
8. [ ] README coverage matrix + handoff summary

## Log
