#!/usr/bin/env bash
# Run ON the server: build the jar with the host JDK, rebuild the api image, roll it out, install nginx config.
set -euo pipefail
cd "$(dirname "$0")/.."
( cd api && mvn -q -B -DskipTests package )
( cd infra && docker compose up -d --build api )
./scripts/render-nginx.sh
for i in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null | grep -q UP; then echo "api ready"; exit 0; fi
  sleep 3
done
echo "api did not become ready"; docker logs --tail 50 inbound-api; exit 1
