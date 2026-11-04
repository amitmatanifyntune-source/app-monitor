# Deploy App Monitor with Docker (Ubuntu 24.04)

One server runs three containers. Only Caddy is published; the database and the server stay on Docker's
internal network.

```
 internet ──443/80──► web (Caddy, automatic HTTPS) ──► server (app-monitor, :8095) ──► db (Postgres 16)
 agents   ──443────►                                     UI + API + agent jar          volume: pgdata
```

| Container | Image | Data |
|---|---|---|
| `web` | `caddy:2.10.2-alpine` | certificates in volume `caddy_data` |
| `server` | built from this repo (`Dockerfile`: UI, server and `fyntune-agent.jar` in one image) | none |
| `db` | `postgres:16-alpine` | volume `pgdata` |

## Server size
2 vCPU, 4 GB RAM, 30 GB disk is enough for a handful of apps. 1 vCPU / 2 GB works too; `install.sh` adds 2 GB of
swap so the image build does not run out of memory. Open ports 22, 80 and 443 (security group or `ufw`).

## Install
1. Point a DNS name at the server (e.g. `monitor.example.com`). No domain yet: skip this and `install.sh` uses
   `<ip-with-dashes>.sslip.io`, which also gets a real certificate.
2. On the server:
   ```bash
   git clone https://github.com/amitmatanifyntune-source/app-monitor.git
   cd app-monitor/deploy/docker
   sudo ./install.sh monitor.example.com
   ```
   It installs Docker, writes `.env` with random secrets, builds the image (5–10 minutes the first time) and starts
   everything.
3. Open `https://monitor.example.com`, sign in as `admin` with the password from `sudo cat admin-password`, and change
   it. Then follow `docs/onboarding-guide.md` to add applications.

Agents connect to `https://monitor.example.com` (outbound 443 from the app servers).

## Everyday commands (run in this folder)
| Task | Command |
|---|---|
| Status | `sudo docker compose ps` |
| Logs | `sudo docker compose logs -f server` |
| Restart | `sudo docker compose restart server` |
| Update to the latest code | `sudo ./update.sh` (backup, pull, build, restart, health check, automatic rollback) |
| Stop / start | `sudo docker compose down` / `sudo docker compose up -d` (data is kept) |
| Backup now | `sudo ./backup.sh` |
| Daily backup at 02:30 | `echo "30 2 * * * root $(pwd)/backup.sh" \| sudo tee /etc/cron.d/app-monitor-backup` |
| Restore | `gunzip -c backups/<file>.sql.gz \| sudo docker compose exec -T db psql -U monitor -d app_monitor` |

Copy `backups/` off the server too (S3, another host). `docker compose down -v` deletes the data volumes: never run
it on a live monitor.

## Settings
`.env` holds `SITE`, `DB_PASSWORD`, `APP_JWT_SECRET`, `MONITOR_ADMIN_PASSWORD` and optional `SERVER_MEM_LIMIT`,
`WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`. After a change: `sudo docker compose up -d`. Keep `.env` and
`admin-password` private; they are git-ignored. `MONITOR_ADMIN_PASSWORD` is used only to create the first
administrator, so changing it later does nothing.

## Moving from the EC2 user-data install
The older `deploy/aws/monitor-userdata.sh` runs the server as a systemd Java service with Postgres and Caddy in
Docker. To move: back up with `docker exec app-monitor-db pg_dump -U monitor app_monitor | gzip > old.sql.gz`, stop
`app-monitor` and the two containers, install as above, then restore into the new `db` with the Restore command.
