#!/usr/bin/env bash
# Run ON the server once per domain: obtain a Let's Encrypt cert via the webroot challenge, then re-render nginx.
set -euo pipefail
cd "$(dirname "$0")/.."
DOMAIN="${1:?usage: issue-cert.sh <domain>}"
grep -q '^DEMO_DOMAIN=' infra/.env 2>/dev/null && sed -i "s/^DEMO_DOMAIN=.*/DEMO_DOMAIN=${DOMAIN}/" infra/.env || echo "DEMO_DOMAIN=${DOMAIN}" >> infra/.env
./scripts/render-nginx.sh
sudo certbot certonly --webroot -w /var/www/demo -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring
sudo mkdir -p /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh >/dev/null
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
./scripts/render-nginx.sh
