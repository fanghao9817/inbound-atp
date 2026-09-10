#!/usr/bin/env bash
# Run ON the server. Renders the nginx site: HTTP only, or HTTP→HTTPS redirect + TLS when a cert exists.
# DEMO_DOMAIN comes from infra/.env (e.g. demo.haoyufang.dev, later the real domain).
set -euo pipefail
cd "$(dirname "$0")/.."
DEMO_DOMAIN="${DEMO_DOMAIN:-$(grep -E '^DEMO_DOMAIN=' infra/.env 2>/dev/null | cut -d= -f2- || true)}"
DEMO_DOMAIN="${DEMO_DOMAIN:-_}"
BODY="$(cat infra/nginx/site-body.conf)"
CERT="/etc/letsencrypt/live/${DEMO_DOMAIN}/fullchain.pem"
if [ "$DEMO_DOMAIN" != "_" ] && sudo test -f "$CERT"; then
  HTTP_BODY="    location / { return 301 https://\$host\$request_uri; }"
  TLS_BLOCK="server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${DEMO_DOMAIN};
    ssl_certificate     /etc/letsencrypt/live/${DEMO_DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DEMO_DOMAIN}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_timeout 1d;
    ssl_session_cache shared:demo:5m;
    ssl_session_tickets off;
    add_header Strict-Transport-Security \"max-age=31536000\" always;
${BODY}
}"
else
  HTTP_BODY="$BODY"
  TLS_BLOCK=""
fi
python3 - "$DEMO_DOMAIN" "$HTTP_BODY" "$TLS_BLOCK" <<'PY' > /tmp/demo.conf
import sys
domain, http_body, tls = sys.argv[1:4]
t = open("infra/nginx/demo.conf.template").read()
print(t.replace("__DOMAIN__", domain).replace("__HTTP_BODY__", http_body.strip()).replace("__TLS_BLOCK__", tls))
PY
sudo install -m 644 /tmp/demo.conf /etc/nginx/sites-available/demo
sudo nginx -t && sudo systemctl reload nginx
echo "nginx rendered for ${DEMO_DOMAIN} (tls: $([ -n "$TLS_BLOCK" ] && echo yes || echo no))"
