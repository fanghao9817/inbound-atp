# Shared helpers for the scripts that run ON the server. Source it: . "$(dirname "$0")/lib.sh"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/infra/.env"

env_get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }

env_set() {   # env_set KEY VALUE - replace or append, never echoing the value
  if grep -qE "^$1=" "$ENV_FILE"; then sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi
}

# POST to the internal API on the box (never exposed by nginx)
internal_post() {
  curl -fsS -X POST -H "X-Internal-Token: $(env_get INTERNAL_API_TOKEN)" "http://127.0.0.1:8080/api/internal/$1"
  echo
}

dbt_pg() {
  ( cd "$ROOT/data" && DBT_PG_HOST=127.0.0.1 DBT_PG_PASSWORD="$(env_get POSTGRES_PASSWORD)" ~/dbt-venv/bin/dbt "$@" --profiles-dir . )
}

log() { echo "[$(date '+%F %T %Z')] $*"; }
