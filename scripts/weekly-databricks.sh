#!/usr/bin/env bash
# Run ON the server by inbound-weekly-databricks.timer (Sunday 03:00 Vancouver). Databricks Free
# Edition cannot reach the box's database, so the lane models run there on a weekly snapshot: export
# the source tables to dbt seeds (never committed), load them, build. Needs ~/.config/inbound-atp/databricks.env.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CONF=~/.config/inbound-atp/databricks.env
[ -f "$CONF" ] || { log "no $CONF, skipping"; exit 0; }
set -a; . "$CONF"; set +a

# Free Edition stops serving compute to a workspace nobody has used in the web UI for a few days
# (denyReason INACTIVE); API and dbt calls don't count as use. Ask before exporting anything, and skip
# with exit 75 (the unit treats it as success, so the journal tells a skip from a build) instead of
# failing every week. Starting the warehouse here is what dbt would do first anyway.
resp=$(curl -sS -m 30 -X POST -H "Authorization: Bearer $DBT_DATABRICKS_TOKEN" \
  "https://${DBT_DATABRICKS_HOST#https://}/api/2.0/sql/warehouses/${DBT_DATABRICKS_HTTP_PATH##*/}/start" 2>&1 || true)
if grep -Eq '"denyReason" *: *"INACTIVE"' <<<"$resp"; then
  log "SKIPPED: the Databricks Free Edition workspace is suspended for inactivity (no web UI login for a few days)."
  log "Log in to the Databricks web UI, then run: sudo systemctl start inbound-weekly-databricks"
  exit 75
fi

cd "$ROOT/data"
for t in fulfillment_center purchase_order shipment shipment_milestone; do
  docker exec inbound-postgres psql -q -U inbound -d inbound -v ON_ERROR_STOP=1 \
    -c "\\copy (select * from public.$t order by id) to stdout with (format csv, header)" > "seeds/src_$t.csv"
done
log "exported $(cat seeds/src_*.csv | wc -l) rows"
~/dbt-venv/bin/dbt seed --profiles-dir . --target databricks --full-refresh --quiet
~/dbt-venv/bin/dbt build --profiles-dir . --target databricks --exclude resource_type:seed --quiet
log "databricks build done"
