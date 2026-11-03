#!/usr/bin/env bash
# Installs the Fyntune agent as a systemd service WITHOUT Docker. It installs its own Java 21 next to any Java the
# server already has (e.g. an app on Java 17) and runs with that Java 21 explicitly; the app's Java is not touched.
# Put next to this script: fyntune-agent.jar and agent.json (both from the App Monitor).
#   sudo ./install-systemd.sh
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
HOME_DIR=/opt/fyntune-agent

[ -f fyntune-agent.jar ] || { echo "Missing fyntune-agent.jar here (App Monitor -> Help -> Download agent)."; exit 1; }
[ -f agent.json ]        || { echo "Missing agent.json here (App Monitor -> Setup -> Agents -> Register)."; exit 1; }

echo "==> Java 21 (alongside any existing Java; the system default is left as it is)"
DEFAULT_JAVA=$(readlink -f "$(command -v java 2>/dev/null)" 2>/dev/null || true)
apt-get update -y && apt-get install -y --no-install-recommends openjdk-21-jre-headless
JAVA21=$(ls -d /usr/lib/jvm/java-21-openjdk-*/bin/java | head -1)
"$JAVA21" -version 2>&1 | head -1
if [ -n "$DEFAULT_JAVA" ] && [ "$(readlink -f "$(command -v java)")" != "$DEFAULT_JAVA" ]; then
  # installing 21 must not change what `java` means for the app
  update-alternatives --set java "$DEFAULT_JAVA"
  echo "  kept the system default java: $DEFAULT_JAVA"
fi

echo "==> User and files"
id fyntune-agent >/dev/null 2>&1 || useradd --system --home "$HOME_DIR" --shell /usr/sbin/nologin fyntune-agent
usermod -aG adm fyntune-agent                                  # /var/log/nginx, /var/log/apache2
getent group www-data >/dev/null && usermod -aG www-data fyntune-agent   # PHP app logs
mkdir -p "$HOME_DIR/spool"
install -m 644 fyntune-agent.jar "$HOME_DIR/fyntune-agent.jar"
[ -f "$HOME_DIR/agent.json" ] || install -m 600 agent.json "$HOME_DIR/agent.json"
python3 - "$HOME_DIR/agent.json" <<'PY'
import json, sys
p = sys.argv[1]; c = json.load(open(p))
if c.get("spoolDir") in (None, "./spool", "/data"):
    c["spoolDir"] = "/opt/fyntune-agent/spool"; json.dump(c, open(p, "w"), indent=2)
PY
chown -R fyntune-agent: "$HOME_DIR"

echo "==> Self-test"
sudo -u fyntune-agent "$JAVA21" -jar "$HOME_DIR/fyntune-agent.jar" "$HOME_DIR/agent.json" --test \
  || { echo; echo "Fix the lines marked with a cross above (edit $HOME_DIR/agent.json), then run again."; exit 1; }

echo "==> Service"
cat > /etc/systemd/system/fyntune-agent.service <<UNIT
[Unit]
Description=Fyntune App Monitor agent
After=network-online.target
Wants=network-online.target

[Service]
User=fyntune-agent
WorkingDirectory=$HOME_DIR
ExecStart=$JAVA21 -Xmx128m -jar $HOME_DIR/fyntune-agent.jar $HOME_DIR/agent.json
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
systemctl enable --now fyntune-agent
systemctl --no-pager status fyntune-agent | head -5
echo
echo "Agent running with $JAVA21. Logs: journalctl -u fyntune-agent -f"
