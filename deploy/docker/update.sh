#!/usr/bin/env bash
# One command to update the monitor to the latest code on GitHub, with no reinstall:
#   cd app-monitor/deploy/docker && sudo ./update.sh
#   sudo ./update.sh --force      rebuild even when there is nothing new
#
# 1. backs up the database (backup.sh)        4. restarts: database migrations run on start-up
# 2. pulls the latest main from GitHub        5. waits until the server is healthy
# 3. builds the new image (the old one runs)  6. if it does not become healthy: back to the previous code and image
# Settings (.env), data and certificates stay. Downtime is the restart only, usually under a minute. Afterwards the
# monitor hands out the new agent and kit, and client servers update with: sudo fyntune-update
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
[ -f .env ] || { echo "No .env here: install first with install.sh."; exit 1; }
FORCE=0; [ "${1:-}" = "--force" ] && FORCE=1

REPO="$(git rev-parse --show-toplevel)"
OWNER="$(stat -c %U "$REPO/.git")"
git_() { sudo -u "$OWNER" -H git -C "$REPO" "$@"; }   # as the repo's owner, with their GitHub key

echo "==> Checking GitHub"
git_ fetch --quiet origin main
OLD="$(git_ rev-parse --short HEAD)"; NEW="$(git_ rev-parse --short origin/main)"
if [ "$OLD" = "$NEW" ] && [ "$FORCE" = 0 ]; then echo "Already up to date ($OLD)."; exit 0; fi
[ "$OLD" != "$NEW" ] && { echo "    $OLD -> $NEW:"; git_ log --oneline "$OLD..origin/main" | sed 's/^/      /' | head -20; }

echo "==> Backup"
./backup.sh | sed 's/^/    /'

echo "==> Pulling and building (the running monitor is not touched yet)"
docker image inspect app-monitor-server:latest >/dev/null 2>&1 && docker tag app-monitor-server:latest app-monitor-server:previous
git_ merge --ff-only --quiet origin/main
if ! docker compose build --quiet; then
  echo "    ! the build failed: going back to $OLD; the running monitor was not changed"
  git_ reset --quiet --hard "$OLD"
  exit 1
fi

echo "==> Restarting"
docker compose up -d --remove-orphans
healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q server)" 2>/dev/null)" = healthy ]; }
for _ in $(seq 1 60); do healthy && break; sleep 5; done
if healthy; then
  docker compose ps
  echo
  echo "Monitor updated to $(git_ rev-parse --short HEAD). Client servers: sudo fyntune-update"
  docker image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "    ! the new version did not become healthy within 5 minutes: going back to $OLD"
docker compose logs --tail 40 server | sed 's/^/      /'
git_ reset --quiet --hard "$OLD"
if docker image inspect app-monitor-server:previous >/dev/null 2>&1; then
  docker tag app-monitor-server:previous app-monitor-server:latest
  docker compose up -d --no-build server
fi
echo "    The previous version is running again. If the new version had already changed the database, restore the"
echo "    backup taken above: gunzip -c backups/<newest>.sql.gz | sudo docker compose exec -T db psql -U monitor -d app_monitor"
exit 1
