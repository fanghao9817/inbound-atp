#!/usr/bin/env bash
# Run ON the server by inbound-weekly-databricks.timer (Sunday 03:00 Vancouver). Databricks Free
# Edition cannot reach the box's database, so the lane models run there on a weekly snapshot: export
# the source tables to dbt seeds (never committed), load them, build. Needs ~/.config/inbound-atp/databricks.env.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CONF=~/.config/inbound-atp/databricks.env
[ -f "$CONF" ] || { log "no $CONF, skipping"; exit 0; }
set -a; . "$CONF"; set +a
cd "$ROOT/data"
for t in fulfillment_center purchase_order shipment shipment_milestone; do
  docker exec inbound-postgres psql -q -U inbound -d inbound -v ON_ERROR_STOP=1 \
    -c "\\copy (select * from public.$t order by id) to stdout with (format csv, header)" > "seeds/src_$t.csv"
done
log "exported $(cat seeds/src_*.csv | wc -l) rows"
~/dbt-venv/bin/dbt seed --profiles-dir . --target databricks --full-refresh --quiet
~/dbt-venv/bin/dbt build --profiles-dir . --target databricks --exclude resource_type:seed --quiet
log "databricks build done"
