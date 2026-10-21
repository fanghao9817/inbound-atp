#!/usr/bin/env bash
# Run ON the server: give the personal login roles listed in infra/.env (DB_OPERATOR_ROLES, separated by
# commas or spaces - e.g. an account used from Navicat through an SSH tunnel) their privileges on the
# public and analytics schemas again. Idempotent; a listed role that does not exist is skipped.
# reset-demo.sh drops and recreates both schemas, which takes every grant on them with it, so it calls
# this afterwards; nightly.sh calls it after dbt rebuilds the analytics tables.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
roles=$(env_get DB_OPERATOR_ROLES | tr ',' ' ')
[ -n "${roles// /}" ] || exit 0
for r in $roles; do
  [[ "$r" =~ ^[a-z_][a-z0-9_]*$ ]] || { log "skipping invalid role name '$r'"; continue; }
  docker exec -i inbound-postgres psql -q -U inbound -d inbound -v ON_ERROR_STOP=1 -v role="$r" <<'SQL'
select exists (select 1 from pg_roles where rolname = :'role') as role_exists \gset
\if :role_exists
grant connect on database inbound to :"role";
grant usage on schema public, analytics to :"role";
grant select, insert, update, delete on all tables in schema public, analytics to :"role";
grant usage, select on all sequences in schema public to :"role";
alter default privileges for role inbound in schema public grant select, insert, update, delete on tables to :"role";
alter default privileges for role inbound in schema analytics grant select, insert, update, delete on tables to :"role";
alter default privileges for role inbound in schema public grant usage, select on sequences to :"role";
\else
\echo role :role does not exist, skipped
\endif
SQL
  log "operator grants applied for $r"
done
