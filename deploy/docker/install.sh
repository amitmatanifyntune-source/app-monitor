#!/usr/bin/env bash
# Installs App Monitor on a fresh Ubuntu 24.04 server with Docker Compose.
#   git clone https://github.com/amitmatanifyntune-source/app-monitor.git
#   cd app-monitor/deploy/docker && sudo ./install.sh monitor.example.com
# Safe to run again: it keeps an existing .env (secrets, admin password) and the data volumes.
set -euo pipefail
cd "$(dirname "$0")"

SITE="${1:-}"
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }

echo "==> Docker"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2 openssl curl
fi
systemctl enable --now docker

echo "==> Swap (helps the Java and Node builds on small servers)"
if ! swapon --show | grep -q . ; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "==> Settings (.env)"
if [ ! -f .env ]; then
  if [ -z "$SITE" ]; then
    IP=$(curl -fs -4 https://checkip.amazonaws.com || true)
    [ -n "$IP" ] && SITE="${IP//./-}.sslip.io" || { echo "Give the site name: sudo ./install.sh monitor.example.com"; exit 1; }
    echo "  no site name given; using $SITE"
  fi
  ADMIN_PW="Am-$(openssl rand -hex 8)"
  umask 077
  cat > .env <<ENV
SITE=$SITE
DB_PASSWORD=$(openssl rand -hex 24)
APP_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
MONITOR_ADMIN_PASSWORD=$ADMIN_PW
ENV
  echo "$ADMIN_PW" > admin-password
  echo "  created .env and admin-password (both readable by root only)"
else
  echo "  keeping the existing .env"
fi

echo "==> Build and start"
docker compose up -d --build

echo "==> Waiting for the server"
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q server)" 2>/dev/null)" = healthy ] && break
  sleep 5
done
docker compose ps

SITE=$(grep '^SITE=' .env | cut -d= -f2)
echo
echo "App Monitor: https://$SITE   (user: admin, password: sudo cat $(pwd)/admin-password)"
echo "Ports 80 and 443 must be open to the internet for the HTTPS certificate."
