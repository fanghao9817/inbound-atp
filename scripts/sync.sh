#!/usr/bin/env bash
# rsync the working tree to the demo box (build artifacts, secrets and VCS metadata excluded)
set -euo pipefail
HOST=34.208.44.222
KEY="${KEY:-/home/fangh/workspace/申请/Article申请/LightsailDefaultKey-us-west-2.pem}"
SRC="${SRC:-$(cd "$(dirname "$0")/.." && pwd)/}"
DEST="${DEST:-/home/ubuntu/inbound-atp}"   # DEST=/home/ubuntu/inbound-atp-dev to test a branch without touching the live working copy
rsync -az --delete \
  --exclude .git --exclude api/target --exclude web/node_modules --exclude web/dist \
  --exclude .venv --exclude data/target --exclude data/logs --exclude '*.pem' --exclude infra/.env \
  -e "ssh -i $KEY -o BatchMode=yes" "$SRC" "ubuntu@$HOST:$DEST/"
echo "synced -> ubuntu@$HOST:$DEST"
