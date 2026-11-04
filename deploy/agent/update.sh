#!/usr/bin/env bash
# One command to bring this server's App Monitor agent up to date, with no reinstall:
#   sudo fyntune-update                 latest kit (scripts, Dockerfile, compose file) and agent from your monitor
#   sudo fyntune-update --auto on       also update every night (about 03:00, randomised by up to an hour)
#   sudo fyntune-update --auto off      stop the nightly update
#
# The kit and agent come from the monitor this agent reports to, fetched with the agent's own id and key from
# agent.json (checksums verified; no password, no access to the code repository needed). Settings stay as they are:
# agent.json (logs, database, OpenTelemetry) and the Docker .env are never replaced. Every changed file is backed up
# under /var/backups/fyntune-agent/. Setup installs this as /usr/local/bin/fyntune-update; the first time on an older
# install, run it from a new kit folder: sudo ./update.sh
set -euo pipefail

SELF="$(readlink -f "$0")"
HERE="$(dirname "$SELF")"
AGENT_DIR="${AGENT_DIR:-}"
AUTO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --agent-dir) AGENT_DIR="$2"; shift ;;
    --auto) AUTO="${2:-}"; shift ;;
    -h|--help) sed -n '2,12p' "$SELF"; exit 0 ;;
    *) echo "Unknown option $1 (see --help)"; exit 1 ;;
  esac
  shift
done
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
# the agent's folder: given, or where this script lives (installed copy), or the standard place
if [ -z "$AGENT_DIR" ]; then
  if [ -f "$HERE/agent.json" ]; then AGENT_DIR="$HERE"; else AGENT_DIR=/opt/fyntune-agent; fi
fi
[ -f "$AGENT_DIR/agent.json" ] || { echo "No agent in $AGENT_DIR (pass --agent-dir, or install it with setup-client.sh)."; exit 1; }
note() { echo "    $*"; }
warn() { echo "    ! $*"; }

# ------------------------------------------------------------------ nightly updates
if [ -n "$AUTO" ]; then
  case "$AUTO" in
    on)
      cat > /etc/systemd/system/fyntune-update.service <<UNIT
[Unit]
Description=Update the App Monitor agent from its monitor
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/fyntune-update --agent-dir $AGENT_DIR
Nice=10
UNIT
      cat > /etc/systemd/system/fyntune-update.timer <<'UNIT'
[Unit]
Description=Nightly App Monitor agent update

[Timer]
OnCalendar=*-*-* 03:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
UNIT
      systemctl daemon-reload && systemctl enable --now fyntune-update.timer >/dev/null
      echo "Nightly update on: $(systemctl list-timers fyntune-update.timer --no-pager | sed -n 2p | awk '{print $1, $2, $3}') next. Log: journalctl -u fyntune-update" ;;
    off)
      systemctl disable --now fyntune-update.timer >/dev/null 2>&1 || true
      rm -f /etc/systemd/system/fyntune-update.timer /etc/systemd/system/fyntune-update.service
      systemctl daemon-reload
      echo "Nightly update off." ;;
    *) echo "--auto on or --auto off"; exit 1 ;;
  esac
  exit 0
fi

echo "==> Latest kit from the monitor"
KIT="$AGENT_DIR/kit"
TMP="$(mktemp -d /tmp/fyntune-kit.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
if OUT="$(python3 - "$AGENT_DIR/agent.json" "$TMP" <<'PY'
import hashlib, io, json, os, sys, urllib.error, urllib.request, zipfile
s = json.load(open(sys.argv[1]))["server"]
req = urllib.request.Request(s["url"].rstrip("/") + "/ingest/v1/agent-kit.zip",
                             headers={"X-Agent-Id": s["agentId"], "X-Agent-Key": s["agentKey"], "User-Agent": "fyntune-update"})
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        data, want, ver, ctype = r.read(), r.headers.get("X-Kit-Sha256"), r.headers.get("X-Agent-Version", "?"), r.headers.get("Content-Type", "")
except urllib.error.HTTPError as e:
    print(f"the monitor answered HTTP {e.code}"); sys.exit(1)
except Exception as e:
    print(f"cannot reach the monitor: {type(e).__name__}: {e}"); sys.exit(1)
if "octet-stream" not in ctype or not want:
    print("the monitor is too old to hand out the kit (update the monitor first)"); sys.exit(1)
if hashlib.sha256(data).hexdigest() != want:
    print("download damaged (checksum mismatch)"); sys.exit(1)
z = zipfile.ZipFile(io.BytesIO(data))
for n in z.namelist():
    if "/" in n or n.startswith(".") or n in ("agent.json", ".env"):
        continue   # flat kit only; never the agent config itself
    open(os.path.join(sys.argv[2], n), "wb").write(z.read(n))
print(ver)
PY
)"; then
  chmod 755 "$TMP"/*.sh 2>/dev/null || true
  BACKUP="/var/backups/fyntune-agent/$(date +%Y%m%d-%H%M%S)-kit"
  if [ -d "$KIT" ]; then mkdir -p "$BACKUP" && cp -a "$KIT" "$BACKUP/"; fi
  rm -rf "$KIT.new" && mkdir -p "$KIT.new" && cp -a "$TMP"/. "$KIT.new"/ && rm -rf "$KIT" && mv "$KIT.new" "$KIT"
  note "kit updated (goes with agent $OUT)"
elif [ -f "$HERE/update-agent.sh" ] && [ "$HERE" != "$AGENT_DIR" ]; then
  warn "$OUT"
  warn "using the kit this script came from instead: $HERE"
  KIT="$HERE"
else
  warn "$OUT"
  if [ -f "$KIT/update-agent.sh" ]; then warn "carrying on with the kit already on this server"
  else echo "    ! no kit available: update the monitor first, or run this from a new kit folder"; exit 1; fi
fi

# this command itself, for next time (a new file, so the copy running now is not disturbed)
if [ -f "$KIT/update.sh" ] && [ "$(readlink -f "$KIT/update.sh")" != "$AGENT_DIR/update.sh" ]; then
  cp "$KIT/update.sh" "$AGENT_DIR/update.sh.new" && chmod 755 "$AGENT_DIR/update.sh.new" && mv -f "$AGENT_DIR/update.sh.new" "$AGENT_DIR/update.sh"
  ln -sf "$AGENT_DIR/update.sh" /usr/local/bin/fyntune-update
fi

echo
"$KIT/update-agent.sh" --agent-dir "$AGENT_DIR"
echo
echo "Up to date. Next time: sudo fyntune-update   (nightly: sudo fyntune-update --auto on)"
