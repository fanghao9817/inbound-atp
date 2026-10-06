#!/usr/bin/env bash
# Run ON the server by inbound-nightly.timer (00:30 Vancouver), after the API's own 00:05 ETA refresh.
# Backup, rebuild the dbt models on the day's data, test them, check the feeds are fresh, republish
# the lineage docs, then re-score open containers with the new lane statistics.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$ROOT"
log "backup";            ./scripts/backup-db.sh nightly
log "dbt build";         dbt_pg build --quiet
log "source freshness";  dbt_pg source freshness
log "dbt docs";          ./scripts/publish-dbt-docs.sh
log "re-score";          internal_post eta/recalculate-all
log "nightly done"
