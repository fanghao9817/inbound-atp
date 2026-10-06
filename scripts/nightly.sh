#!/usr/bin/env bash
# Run ON the server by inbound-nightly.timer (00:30 Vancouver), after the API's own 00:05 ETA refresh.
# Backup, rebuild and test the dbt models on the day's data, republish the lineage docs, re-score open
# containers with the new lane statistics, check the feeds are fresh. A failing step does not stop the
# later ones; the unit fails at the end (journalctl -u inbound-nightly).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$ROOT"
rc=0
log "backup";            ./scripts/backup-db.sh nightly
log "dbt build";         dbt_pg build --quiet          || { log "dbt build FAILED"; rc=1; }
log "dbt docs";          ./scripts/publish-dbt-docs.sh || rc=1
log "re-score";          internal_post eta/recalculate-all || rc=1
log "source freshness";  dbt_pg source freshness       || { log "sources STALE"; rc=1; }
log "nightly done (rc=$rc)"
exit $rc
