#!/usr/bin/env bash
# Run ON the server. Custom-format dump of the whole database (all schemas stay mutually consistent).
# Keeps the newest 14 dumps. Called before every deploy and by the nightly job.
set -euo pipefail
DIR="${BACKUP_DIR:-$HOME/backups}"
mkdir -p "$DIR" && chmod 700 "$DIR"
FILE="$DIR/inbound-$(date +%F-%H%M%S)${1:+-$1}.dump"
docker exec inbound-postgres pg_dump -U inbound -d inbound -Fc > "$FILE.tmp" && mv "$FILE.tmp" "$FILE"
ls -1t "$DIR"/inbound-*.dump | tail -n +15 | xargs -r rm -f
echo "backup: $FILE ($(du -h "$FILE" | cut -f1))"
