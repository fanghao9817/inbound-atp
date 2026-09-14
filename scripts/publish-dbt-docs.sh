#!/usr/bin/env bash
# Run ON the server after a dbt build: regenerate the lineage docs (PostgreSQL target) and publish under /dbt/.
set -euo pipefail
cd "$(dirname "$0")/../data"
export DBT_PG_HOST=127.0.0.1 DBT_PG_PASSWORD=$(grep POSTGRES_PASSWORD ../infra/.env | cut -d= -f2)
~/dbt-venv/bin/dbt docs generate --profiles-dir . --quiet
sudo mkdir -p /var/www/dbt && sudo chown -R "$USER":"$USER" /var/www/dbt
cp target/index.html target/manifest.json target/catalog.json /var/www/dbt/
echo "dbt docs published -> /dbt/"
