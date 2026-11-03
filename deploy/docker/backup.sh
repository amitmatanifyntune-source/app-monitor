#!/usr/bin/env bash
# Dumps the monitor database to ./backups/app_monitor-<date>.sql.gz and keeps the last 14.
# Daily at 02:30:  echo "30 2 * * * root $(pwd)/backup.sh" | sudo tee /etc/cron.d/app-monitor-backup
# Restore:        gunzip -c backups/<file>.sql.gz | docker compose exec -T db psql -U monitor -d app_monitor
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
FILE="backups/app_monitor-$(date +%Y%m%d-%H%M).sql.gz"
docker compose exec -T db pg_dump -U monitor -d app_monitor | gzip > "$FILE"
chmod 600 "$FILE"
ls -1t backups/app_monitor-*.sql.gz | tail -n +15 | xargs -r rm --
echo "backup: $FILE"
