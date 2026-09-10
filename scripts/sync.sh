#!/usr/bin/env bash
# rsync the working tree to the demo box (build artifacts, secrets and VCS metadata excluded)
set -euo pipefail
HOST=34.208.44.222
KEY="${KEY:-/home/fangh/workspace/申请/Article申请/LightsailDefaultKey-us-west-2.pem}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/"
rsync -az --delete \
  --exclude .git --exclude api/target --exclude web/node_modules --exclude web/dist \
  --exclude .venv --exclude data/target --exclude data/logs --exclude '*.pem' --exclude infra/.env \
  -e "ssh -i $KEY -o BatchMode=yes" "$SRC" ubuntu@$HOST:/home/ubuntu/inbound-atp/
echo "synced -> ubuntu@$HOST:/home/ubuntu/inbound-atp"
