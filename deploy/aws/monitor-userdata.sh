#!/usr/bin/env bash
# EC2 user data for the App Monitor server (Ubuntu 24.04). Fill in the three values below, then paste the whole file
# into Launch instance -> Advanced details -> User data. Runs once on first boot; log: /var/log/app-monitor-setup.log
#   SITE        the Elastic IP with dashes + .sslip.io, e.g. 13-55-12-34.sslip.io (Let's Encrypt issues HTTPS for it)
#   SERVER_JAR  a presigned S3 URL for app-monitor-server.jar
#   AGENT_JAR   a presigned S3 URL for fyntune-agent.jar (served to administrators from Setup and Help)
SITE="REPLACE.sslip.io"
SERVER_JAR_URL="REPLACE"
AGENT_JAR_URL="REPLACE"

set -euo pipefail
exec > >(tee -a /var/log/app-monitor-setup.log) 2>&1
HOME_DIR=/opt/app-monitor

echo "==> Packages"
apt-get update -y
apt-get install -y docker.io openjdk-21-jre-headless curl openssl
systemctl enable --now docker

echo "==> Swap"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "==> Files"
id app-monitor >/dev/null 2>&1 || useradd --system --home "$HOME_DIR" --shell /usr/sbin/nologin app-monitor
mkdir -p "$HOME_DIR"
curl -fsSL -o "$HOME_DIR/app-monitor-server.jar" "$SERVER_JAR_URL"
curl -fsSL -o "$HOME_DIR/fyntune-agent.jar" "$AGENT_JAR_URL"

if [ ! -f "$HOME_DIR/env" ]; then
  echo "==> Secrets"
  DB_PW=$(openssl rand -hex 24)
  ADMIN_PW="Am-$(openssl rand -hex 8)"
  cat > "$HOME_DIR/env" <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/app_monitor
DB_USER=monitor
DB_PASSWORD=$DB_PW
APP_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
MONITOR_ADMIN_PASSWORD=$ADMIN_PW
AGENT_JAR=$HOME_DIR/fyntune-agent.jar
APP_PUBLIC_URL=https://$SITE
ENV
  echo "$ADMIN_PW" > "$HOME_DIR/admin-password"
  chmod 600 "$HOME_DIR/env" "$HOME_DIR/admin-password"
fi
chown -R app-monitor: "$HOME_DIR"
set -a; . "$HOME_DIR/env"; set +a

echo "==> Postgres"
docker volume create app-monitor-pg >/dev/null
docker rm -f app-monitor-db >/dev/null 2>&1 || true
docker run -d --name app-monitor-db --restart unless-stopped -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=app_monitor -e POSTGRES_USER=monitor -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -v app-monitor-pg:/var/lib/postgresql/data postgres:16-alpine
for _ in $(seq 1 30); do docker exec app-monitor-db pg_isready -U monitor -q && break; sleep 2; done

echo "==> Service"
cat > /etc/systemd/system/app-monitor.service <<UNIT
[Unit]
Description=Fyntune App Monitor server
After=network-online.target docker.service
Wants=network-online.target

[Service]
User=app-monitor
WorkingDirectory=$HOME_DIR
EnvironmentFile=$HOME_DIR/env
ExecStart=/usr/bin/java -Xmx768m -jar $HOME_DIR/app-monitor-server.jar
Restart=always
RestartSec=10
ProtectSystem=strict
ReadWritePaths=$HOME_DIR
ProtectHome=true
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now app-monitor

echo "==> HTTPS (Caddy)"
docker volume create app-monitor-caddy >/dev/null
docker rm -f app-monitor-web >/dev/null 2>&1 || true
docker run -d --name app-monitor-web --restart unless-stopped --network host -v app-monitor-caddy:/data \
  caddy:2.10.2-alpine caddy reverse-proxy --from "$SITE" --to 127.0.0.1:8095

for _ in $(seq 1 60); do curl -fs http://127.0.0.1:8095/ >/dev/null && break; sleep 3; done
echo "APP_MONITOR_READY https://$SITE"
