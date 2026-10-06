#!/usr/bin/env bash
# Run ON the server: create the runtime secrets in infra/.env if they are missing (idempotent).
#   INTERNAL_API_TOKEN  shared by the API and the simulator for /api/internal/**
#   SIM_SEED            the simulator's private seed: knowing it would reveal every future "random" event
set -euo pipefail
. "$(dirname "$0")/lib.sh"
for k in INTERNAL_API_TOKEN SIM_SEED; do
  [ -n "$(env_get "$k")" ] || { env_set "$k" "$(openssl rand -hex 24)"; log "generated $k"; }
done
chmod 600 "$ENV_FILE"
