#!/usr/bin/env bash
# Installs the Fyntune agent as a Docker container on an Ubuntu 22.04/24.04 app server. No Java needed on the host.
# Put next to this script: fyntune-agent.jar and agent.json (both from the App Monitor: Help and Setup -> Agents).
#   sudo ./install-docker.sh [APP_DIR]        APP_DIR = folder holding the app's logs, default /var/www
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
APP_DIR="${1:-${APP_DIR:-/var/www}}"

[ -f fyntune-agent.jar ] || { echo "Missing fyntune-agent.jar here (App Monitor -> Help -> Download agent)."; exit 1; }
[ -f agent.json ]        || { echo "Missing agent.json here (App Monitor -> Setup -> Agents -> Register)."; exit 1; }
[ -d "$APP_DIR" ]        || { echo "APP_DIR $APP_DIR does not exist; pass the app folder: sudo ./install-docker.sh /srv/app"; exit 1; }

echo "==> Docker"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  apt-get update -y && apt-get install -y docker.io docker-compose-v2
fi
systemctl enable --now docker

echo "==> agent.json for the container"
chmod 600 agent.json
python3 - <<'PY'
import json
p = "agent.json"; c = json.load(open(p)); changed = []
if c.get("spoolDir") != "/data":
    c["spoolDir"] = "/data"; changed.append('spoolDir -> "/data"')
h = c.setdefault("host", {})
disks = h.get("disks") or ["/"]
fixed = [d if d.startswith("/host") else "/host" + ("" if d == "/" else d) for d in disks]
if fixed != disks:
    h["disks"] = fixed; changed.append(f"disks -> {fixed}")
json.dump(c, open(p, "w"), indent=2)
print("  " + ("; ".join(changed) if changed else "already set for Docker"))
PY
echo "APP_DIR=$APP_DIR" > .env

echo "==> Build image (Java 21 inside)"
docker compose build -q

echo "==> Self-test"
docker compose run --rm agent --test || { echo; echo "Fix the lines marked with a cross above, then run this script again."; exit 1; }

echo "==> Start"
docker compose up -d
docker compose ps
echo
echo "Agent running. Logs: sudo docker compose -f $(pwd)/docker-compose.yml logs -f"
