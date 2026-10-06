#!/usr/bin/env bash
# Run ON the server: back up, build the jars with the host JDK, rebuild the images, roll out, and wait
# until every container reports healthy (the compose healthchecks are the source of truth).
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/ensure-secrets.sh
./scripts/backup-db.sh pre-deploy
( cd api && mvn -q -B -DskipTests package )
( cd simulator && mvn -q -B -DskipTests package )
( cd infra && docker compose up -d --build --wait --wait-timeout 300 ) || {
  echo "containers did not become healthy"; docker logs --tail 80 inbound-api; exit 1; }
./scripts/render-nginx.sh
echo "api ready"
