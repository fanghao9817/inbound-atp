#!/usr/bin/env bash
# Build the Vue app locally (or reuse web/dist when --no-build) and publish it to the server's nginx root.
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=34.208.44.222
KEY="${KEY:-/home/fangh/workspace/申请/Article申请/LightsailDefaultKey-us-west-2.pem}"
if [ "${1:-}" != "--no-build" ]; then ( cd web && npm run build ); fi
ssh -i "$KEY" -o BatchMode=yes ubuntu@$HOST 'sudo mkdir -p /var/www/demo && sudo chown -R ubuntu:ubuntu /var/www/demo'
rsync -az --delete --exclude '.well-known' -e "ssh -i $KEY -o BatchMode=yes" web/dist/ ubuntu@$HOST:/var/www/demo/
echo "web published -> https://$(ssh -i "$KEY" -o BatchMode=yes ubuntu@$HOST 'grep DEMO_DOMAIN ~/inbound-atp/infra/.env | cut -d= -f2')/"
