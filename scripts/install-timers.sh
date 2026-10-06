#!/usr/bin/env bash
# Run ON the server (sudo): install or update the systemd units versioned in infra/systemd.
set -euo pipefail
cd "$(dirname "$0")/../infra/systemd"
sudo install -m 644 inbound-*.service inbound-*.timer /etc/systemd/system/
sudo systemctl daemon-reload
for t in inbound-*.timer; do sudo systemctl enable --now "$t"; done
systemctl list-timers --no-pager 'inbound-*'
