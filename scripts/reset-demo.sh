#!/usr/bin/env bash
# Run ON the server. Start the demo over: back up, wipe the database, let the API migrate and seed a
# fresh year of history, rebuild the dbt models, then hand the world to the simulator from this
# moment (SIM_GO_LIVE). Everything before go-live is seeded history; everything after is simulated.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
[ "${1:-}" = "--yes" ] || { echo "Deletes ALL demo data (a backup is taken first). Run: $0 --yes"; exit 1; }
cd "$ROOT"

./scripts/ensure-secrets.sh
./scripts/backup-db.sh pre-reset
cd infra
log "stopping simulator and api"
docker compose stop simulator api
log "dropping schemas"
docker exec inbound-postgres psql -q -U inbound -d inbound -v ON_ERROR_STOP=1 \
  -c "drop schema if exists analytics cascade" -c "drop schema public cascade" -c "create schema public authorization inbound"
log "starting api (Flyway + seed)"
docker compose up -d --wait --wait-timeout 240 api
cd "$ROOT"
log "dbt build"
dbt_pg build --quiet
./scripts/publish-dbt-docs.sh
log "re-score with fresh lane statistics, re-project the storefront"
internal_post eta/recalculate-all
internal_post availability/project-all
env_set SIM_GO_LIVE "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
env_set SIM_ENABLED true
log "starting simulator, go-live $(env_get SIM_GO_LIVE)"
( cd infra && docker compose up -d --wait --wait-timeout 240 simulator )
log "reset complete"
