#!/usr/bin/env bash
# One-shot setup of App Monitor on a client application server (Ubuntu 22.04 / 24.04), with the agent in Docker.
#
#   sudo ./setup-client.sh                      # asks a few questions
#   sudo MONITOR_URL=https://monitor.example.com APP_CODE=billing-portal HEALTH_URL=https://billing.example.com/up \
#        ./setup-client.sh --yes                # no questions (password from MONITOR_PASSWORD or prompted)
#
# What it does, each step skipped when already done (safe to run again):
#   1. registers the app and this server's agent with the monitor (or uses an agent.json you downloaded)
#   2. adds a timed access log to nginx or Apache (a separate fyntune-access.log; existing logs are untouched)
#   3. finds the PHP/Laravel app, its logs, queue workers, database, processes and ports
#   4. creates a read-only database user for statistics (MySQL/MariaDB or PostgreSQL, if root access works)
#   5. installs Docker, builds the agent image (Java 21 inside), runs the agent's self-test, starts it
#   6. adds the health check in the monitor
#   with --otel: installs the PHP OpenTelemetry extension and points PHP-FPM at the agent (Dependencies and Traces tabs)
#
# Options: --yes (no questions)  --no-db  --no-web-log  --laravel-single (switch Laravel's daily log to one file)
#          --otel (OpenTelemetry for PHP; OTEL_SAMPLE=0.25 records a quarter of requests, default all)
# Environment: MONITOR_URL APP_CODE APP_NAME APP_ENV APP_DIR HEALTH_URL HEALTH_TEXT MONITOR_USER MONITOR_PASSWORD
set -euo pipefail

KIT="$(cd "$(dirname "$0")" && pwd)"
TARGET=/opt/fyntune-agent
BACKUP="/var/backups/fyntune-agent/$(date +%Y%m%d-%H%M%S)"
YES=0; NO_DB=0; NO_WEB=0; LARAVEL_SINGLE=0; OTEL=0
for a in "$@"; do
  case "$a" in
    --yes|-y) YES=1 ;; --no-db) NO_DB=1 ;; --no-web-log) NO_WEB=1 ;; --laravel-single) LARAVEL_SINGLE=1 ;; --otel) OTEL=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown option $a"; exit 1 ;;
  esac
done

step() { echo; echo "==> $*"; }
note() { echo "    $*"; }
warn() { echo "    ! $*"; }
ask() {  # ask VAR "question" default
  local var="$1" q="$2" def="${3:-}" cur="${!1:-}"
  [ -n "$cur" ] && return
  if [ "$YES" = 1 ]; then printf -v "$var" '%s' "$def"; return; fi
  local ans; read -r -p "    $q${def:+ [$def]}: " ans || true
  printf -v "$var" '%s' "${ans:-$def}"
}
active() { systemctl is-active --quiet "$1" 2>/dev/null; }

[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
command -v python3 >/dev/null || { apt-get update -y && apt-get install -y python3; }
mkdir -p "$TARGET" "$BACKUP"

# ---------------------------------------------------------------------------------------------------------------
step "1/6 Monitor registration"
EXISTING=""
if [ -f "$TARGET/agent.json" ]; then EXISTING="$TARGET/agent.json"; note "reusing this server's agent from $TARGET/agent.json"
elif [ -f "$KIT/agent.json" ]; then EXISTING="$KIT/agent.json"; note "using agent.json next to this script"
fi

if [ -n "$EXISTING" ]; then
  MONITOR_URL="${MONITOR_URL:-$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["server"]["url"])' "$EXISTING")}"
fi
ask MONITOR_URL "Monitor URL (e.g. https://monitor.example.com)" ""
[ -n "$MONITOR_URL" ] || { echo "MONITOR_URL is required."; exit 1; }
MONITOR_URL="${MONITOR_URL%/}"
ask APP_CODE "App code in the monitor (lower-case, digits, hyphens)" "$(hostname -s | tr 'A-Z_.' 'a-z--')"
ask APP_NAME "App name" "$APP_CODE"
ask APP_ENV "Environment (production, uat, development)" "production"

NEED_API=0
[ -z "$EXISTING" ] && NEED_API=1
[ -f "$KIT/fyntune-agent.jar" ] || [ -f "$TARGET/fyntune-agent.jar" ] || NEED_API=1
ask HEALTH_URL "Health URL only this app answers (blank to add later)" ""
[ -n "$HEALTH_URL" ] && NEED_API=1
MONITOR_USER="${MONITOR_USER:-admin}"
if [ "$NEED_API" = 1 ] && [ -z "${MONITOR_PASSWORD:-}" ]; then
  read -r -s -p "    Monitor password for $MONITOR_USER (used now, not stored): " MONITOR_PASSWORD; echo
fi
export MONITOR_URL APP_CODE APP_NAME APP_ENV HEALTH_URL MONITOR_USER NEED_API EXISTING TARGET KIT
export HEALTH_TEXT="${HEALTH_TEXT:-}" MONITOR_PASSWORD="${MONITOR_PASSWORD:-}"

python3 - <<'PY'
import json, os, socket, ssl, urllib.request, urllib.error
E = os.environ
out = os.path.join(E["TARGET"], "registration.json")
if E["NEED_API"] != "1":
    json.dump({"server": json.load(open(E["EXISTING"]))["server"]}, open(out, "w")); raise SystemExit
base = E["MONITOR_URL"]
def call(method, path, body=None, token=None, raw=False):
    req = urllib.request.Request(base + path, method=method,
        data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30, context=ssl.create_default_context()) as r:
            data = r.read(); return r.status, (data if raw else (json.loads(data) if data else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")
st, res = call("POST", "/api/auth/login", {"username": E["MONITOR_USER"], "password": E["MONITOR_PASSWORD"]})
if st != 200: raise SystemExit(f"    ! sign-in to {base} failed (HTTP {st}). Check the URL, user and password.")
tok = res["token"]; code = E["APP_CODE"]
st, app = call("GET", f"/api/admin/apps/{code}", token=tok)
if st == 404:
    st, app = call("POST", "/api/admin/apps", {"code": code, "name": E["APP_NAME"], "environment": E["APP_ENV"],
                   "description": f"PHP app on {socket.gethostname()}", "slowMs": 2000}, tok)
    if st != 200: raise SystemExit(f"    ! could not add app {code}: {app}")
    print(f"    added app {code}"); app["checks"] = []
else:
    print(f"    app {code} already exists")
if E["EXISTING"]:
    server = json.load(open(E["EXISTING"]))["server"]
else:
    st, reg = call("POST", f"/api/admin/apps/{code}/agents", {"name": socket.gethostname(), "serverUrl": base}, tok)
    if st != 200: raise SystemExit(f"    ! could not register the agent: {reg}")
    aj = reg["agentJson"]; aj = json.loads(aj) if isinstance(aj, str) else aj
    server = aj["server"]; print(f"    registered agent {server['agentId']} for {socket.gethostname()}")
json.dump({"server": server}, open(out, "w"))
jar = os.path.join(E["TARGET"], "fyntune-agent.jar")
if not os.path.isfile(os.path.join(E["KIT"], "fyntune-agent.jar")) and not os.path.isfile(jar):
    st, data = call("GET", "/api/admin/agent/download", token=tok, raw=True)
    if st != 200: raise SystemExit(f"    ! could not download the agent from the monitor: {data}")
    open(jar, "wb").write(data); print("    downloaded fyntune-agent.jar")
if E["HEALTH_URL"] and not any(c.get("health") for c in app.get("checks", [])):
    cfg = {"url": E["HEALTH_URL"], "expectStatus": 200, "maxMs": 2000}
    if E["HEALTH_TEXT"]: cfg["contains"] = E["HEALTH_TEXT"]
    st, r = call("POST", f"/api/admin/apps/{code}/checks",
                 {"name": "Health", "kind": "HTTP", "config": cfg, "intervalSec": 30, "health": True, "enabled": True}, tok)
    print("    added health check" if st == 200 else f"    ! health check not added: {r}")
PY
chmod 600 "$TARGET/registration.json"
unset MONITOR_PASSWORD

# ---------------------------------------------------------------------------------------------------------------
step "2/6 Web server access log (response time + request id)"
ACCESS_LOG=""; ACCESS_FMT=""; WEB=""
if active nginx; then WEB=nginx; elif active apache2; then WEB=apache2; fi
if [ -z "$WEB" ]; then warn "neither nginx nor apache2 is running; no access log (the APIs tab will stay empty)"
elif [ "$NO_WEB" = 1 ]; then warn "skipped (--no-web-log)"
elif [ "$WEB" = nginx ]; then
  ACCESS_LOG=/var/log/nginx/fyntune-access.log; ACCESS_FMT=nginx
  cp -a /etc/nginx "$BACKUP/nginx"
  cat > /etc/nginx/conf.d/fyntune-log.conf <<'NGX'
# App Monitor: an extra access log with response time and request id (read by the fyntune agent)
log_format fyntune '$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent '
                   '"$http_referer" "$http_user_agent" $request_time $request_id';
access_log /var/log/nginx/fyntune-access.log fyntune;
NGX
  # server blocks with their own access_log do not inherit the one above: add it next to theirs
  python3 - /etc/nginx/sites-enabled <<'PY'
import os, re, sys
line = "access_log /var/log/nginx/fyntune-access.log fyntune;"
for name in (sorted(os.listdir(sys.argv[1])) if os.path.isdir(sys.argv[1]) else []):
    p = os.path.realpath(os.path.join(sys.argv[1], name))
    if not os.path.isfile(p): continue
    src = open(p).read().splitlines(keepends=True); out = []; changed = False
    for i, l in enumerate(src):
        out.append(l)
        m = re.match(r"^(\s*)access_log\s+(?!off\b)", l)
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if m and "fyntune" not in l and "fyntune" not in nxt:
            out.append(f"{m.group(1)}{line}\n"); changed = True
    if changed: open(p, "w").write("".join(out)); print(f"    added the fyntune log to {p}")
PY
  if nginx -t >/dev/null 2>&1; then systemctl reload nginx; note "nginx reloaded; log: $ACCESS_LOG"
  else rm -rf /etc/nginx && cp -a "$BACKUP/nginx" /etc/nginx; warn "nginx -t failed; config restored, no access log"; ACCESS_LOG=""; fi
else
  ACCESS_LOG=/var/log/apache2/fyntune-access.log; ACCESS_FMT=apache
  cp -a /etc/apache2 "$BACKUP/apache2"
  a2enmod -q unique_id >/dev/null
  cat > /etc/apache2/conf-available/fyntune-log.conf <<'APA'
# App Monitor: an extra access log with response time (%D, microseconds) and request id
LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\" %D %{UNIQUE_ID}e" fyntune
CustomLog ${APACHE_LOG_DIR}/fyntune-access.log fyntune
APA
  a2enconf -q fyntune-log >/dev/null
  python3 - /etc/apache2/sites-enabled <<'PY'
import os, re, sys
line = "CustomLog ${APACHE_LOG_DIR}/fyntune-access.log fyntune"
for name in (sorted(os.listdir(sys.argv[1])) if os.path.isdir(sys.argv[1]) else []):
    p = os.path.realpath(os.path.join(sys.argv[1], name))
    if not os.path.isfile(p): continue
    src = open(p).read().splitlines(keepends=True); out = []; changed = False
    for i, l in enumerate(src):
        out.append(l)
        m = re.match(r"^(\s*)CustomLog\s+", l, re.I)
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if m and "fyntune" not in l and "fyntune" not in nxt:
            out.append(f"{m.group(1)}{line}\n"); changed = True
    if changed: open(p, "w").write("".join(out)); print(f"    added the fyntune log to {p}")
PY
  if apache2ctl configtest >/dev/null 2>&1; then systemctl reload apache2; note "apache2 reloaded; log: $ACCESS_LOG"
  else rm -rf /etc/apache2 && cp -a "$BACKUP/apache2" /etc/apache2; warn "configtest failed; config restored, no access log"; ACCESS_LOG=""; fi
fi
[ -n "$ACCESS_LOG" ] && { touch "$ACCESS_LOG"; chown --reference="$(dirname "$ACCESS_LOG")" "$ACCESS_LOG" 2>/dev/null || true; }
# Ubuntu's logrotate for nginx/apache2 already covers /var/log/<server>/*.log

# ---------------------------------------------------------------------------------------------------------------
step "3/6 Application, logs, processes and ports"
if [ -z "${APP_DIR:-}" ]; then
  for d in /var/www/*/ /var/www/html/ /srv/*/ /home/*/www/*/; do [ -f "${d}artisan" ] && { APP_DIR="${d%/}"; break; }; done
fi
ask APP_DIR "Application folder" "${APP_DIR:-/var/www}"
APP_LOGS=()
DB_CONN=""; DB_NAME=""
if [ -f "$APP_DIR/artisan" ]; then
  note "Laravel app in $APP_DIR"
  ENVF="$APP_DIR/.env"
  envget() { [ -f "$ENVF" ] && grep -E "^$1=" "$ENVF" | tail -1 | cut -d= -f2- | tr -d '"'"'"; }
  CH="$(envget LOG_CHANNEL)"; STK="$(envget LOG_STACK)"
  if [ "$CH" = daily ] || { [ "$CH" = stack ] && [ "$STK" = daily ]; }; then
    if [ "$LARAVEL_SINGLE" = 1 ]; then
      cp -a "$ENVF" "$BACKUP/laravel.env"
      sed -i -E 's/^LOG_CHANNEL=.*/LOG_CHANNEL=single/; s/^LOG_STACK=.*/LOG_STACK=single/' "$ENVF"
      [ -f "$APP_DIR/bootstrap/cache/config.php" ] && (cd "$APP_DIR" && sudo -u www-data php artisan config:cache >/dev/null)
      note "Laravel logs switched to one file (backup of .env in $BACKUP)"
    else
      warn "Laravel writes daily log files; the agent follows laravel.log only. Re-run with --laravel-single to switch."
    fi
  fi
  LOGF="$APP_DIR/storage/logs/laravel.log"
  [ -d "$(dirname "$LOGF")" ] && { [ -f "$LOGF" ] || install -o www-data -g www-data -m 664 /dev/null "$LOGF"; }
  APP_LOGS+=("$LOGF")
  if [ ! -f /etc/logrotate.d/fyntune-laravel ] && ! grep -rqs "laravel.log" /etc/logrotate.d; then
    cat > /etc/logrotate.d/fyntune-laravel <<LR
$LOGF {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
LR
    note "logrotate added for laravel.log"
  fi
  DB_CONN="$(envget DB_CONNECTION)"; DB_NAME="$(envget DB_DATABASE)"
else
  note "no artisan in $APP_DIR: treating it as a plain PHP app"
fi
for f in /var/log/php*-fpm.log; do [ -f "$f" ] && APP_LOGS+=("$f"); done
[ ${#APP_LOGS[@]} -gt 0 ] && note "app logs: ${APP_LOGS[*]}"

PROCS=()
pgrep -f "php-fpm: master" >/dev/null && PROCS+=("php-fpm|php-fpm: master")
pgrep -f "nginx: master" >/dev/null && PROCS+=("nginx|nginx: master")
pgrep -x apache2 >/dev/null && PROCS+=("apache2|apache2")
pgrep -x mysqld >/dev/null && PROCS+=("MySQL|mysqld")
pgrep -x mariadbd >/dev/null && PROCS+=("MariaDB|mariadbd")
pgrep -f "postgres.*-D" >/dev/null && PROCS+=("PostgreSQL|postgres")
pgrep -x redis-server >/dev/null && PROCS+=("Redis|redis-server")
pgrep -f "artisan queue:work" >/dev/null && PROCS+=("queue worker|artisan queue:work")
pgrep -f "artisan horizon" >/dev/null && PROCS+=("Horizon|artisan horizon")
pgrep -x cron >/dev/null && PROCS+=("cron|cron")
note "processes: $(printf '%s ' "${PROCS[@]%%|*}")"
LISTEN="$(ss -ltnH 2>/dev/null | awk '{print $4}' | sed -E 's/.*:([0-9]+)$/\1/' | sort -un | tr '\n' ' ')"
note "listening ports: $LISTEN"

# ---------------------------------------------------------------------------------------------------------------
step "4/6 Read-only database user"
DB_ENGINE=""; DB_URL=""; DB_USER=""; DB_PASS=""
if [ "$NO_DB" = 1 ]; then warn "skipped (--no-db)"
else
  DB_PASS="$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')"
  if { [ "$DB_CONN" = mysql ] || [ "$DB_CONN" = mariadb ] || { [ -z "$DB_CONN" ] && command -v mysql >/dev/null; }; } \
     && mysql -N -e "SELECT 1" >/dev/null 2>&1; then
    mysql <<SQL
CREATE USER IF NOT EXISTS 'fyntune_monitor'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
ALTER USER 'fyntune_monitor'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
GRANT PROCESS ON *.* TO 'fyntune_monitor'@'127.0.0.1';
GRANT SELECT ON performance_schema.* TO 'fyntune_monitor'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
    DB_ENGINE=mysql; DB_USER=fyntune_monitor; DB_URL="jdbc:mysql://127.0.0.1:3306/${DB_NAME:-mysql}"
    note "MySQL user fyntune_monitor@127.0.0.1 ready (statistics only)"
  elif { [ "$DB_CONN" = pgsql ] || [ -z "$DB_CONN" ]; } && command -v psql >/dev/null && sudo -u postgres psql -Atqc "SELECT 1" >/dev/null 2>&1; then
    DBN="${DB_NAME:-postgres}"
    sudo -u postgres psql -q -v ON_ERROR_STOP=1 -v pw="$DB_PASS" -v db="$DBN" <<'SQL'
SELECT format('CREATE ROLE fyntune_monitor LOGIN PASSWORD %L', :'pw') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fyntune_monitor') \gexec
SELECT format('ALTER ROLE fyntune_monitor LOGIN PASSWORD %L', :'pw') \gexec
GRANT pg_monitor TO fyntune_monitor;
SELECT format('GRANT CONNECT ON DATABASE %I TO fyntune_monitor', :'db') \gexec
SQL
    DB_ENGINE=postgres; DB_USER=fyntune_monitor; DB_URL="jdbc:postgresql://127.0.0.1:5432/$DBN"
    note "PostgreSQL role fyntune_monitor ready (pg_monitor)"
    sudo -u postgres psql -Atqc "SHOW shared_preload_libraries" | grep -q pg_stat_statements \
      || warn "add pg_stat_statements to shared_preload_libraries for query statistics (needs a PostgreSQL restart)"
  else
    warn "no local MySQL/PostgreSQL with root access found; database monitoring skipped"
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
if [ "$OTEL" = 1 ]; then
  step "4b/6 OpenTelemetry for PHP (traces and outbound calls)"
  # the same script turns it on later without a full setup; the agent's otlp setting is written in step 5
  OTEL_SAMPLE="${OTEL_SAMPLE:-1.0}" "$KIT/enable-otel.sh" --service "$APP_CODE" --app-dir "$APP_DIR" --no-agent | sed '/^==>/d; /^Done\./d; /^$/d' || warn "OpenTelemetry step failed; the rest of the setup continues"
fi

# ---------------------------------------------------------------------------------------------------------------
step "5/6 Agent in Docker (Java 21 inside the image)"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  apt-get update -y && apt-get install -y docker.io docker-compose-v2
fi
systemctl enable --now docker >/dev/null
install -m 644 "$KIT/Dockerfile" "$TARGET/Dockerfile"
install -m 644 "$KIT/docker-compose.yml" "$TARGET/docker-compose.yml"
# the kit and the one-command updater, so later updates are just: sudo fyntune-update
mkdir -p "$TARGET/kit"
for f in "$KIT"/*.sh "$KIT"/Dockerfile "$KIT"/docker-compose.yml "$KIT"/README.md "$KIT"/*.example.json; do [ -f "$f" ] && install -m 644 "$f" "$TARGET/kit/"; done
chmod 755 "$TARGET"/kit/*.sh
install -m 755 "$KIT/update.sh" "$TARGET/update.sh" && ln -sf "$TARGET/update.sh" /usr/local/bin/fyntune-update
[ -f "$KIT/fyntune-agent.jar" ] && install -m 644 "$KIT/fyntune-agent.jar" "$TARGET/fyntune-agent.jar"
echo "APP_DIR=$APP_DIR" > "$TARGET/.env"
[ -f "$TARGET/agent.json" ] && cp -a "$TARGET/agent.json" "$BACKUP/agent.json"

export ACCESS_LOG ACCESS_FMT DB_ENGINE DB_URL DB_USER DB_PASS APP_DIR LISTEN
APP_LOGS_JOINED="$(printf '%s\n' "${APP_LOGS[@]}")"; PROCS_JOINED="$(printf '%s\n' "${PROCS[@]}")"
export APP_LOGS_JOINED PROCS_JOINED
python3 - <<'PY'
import json, os
E = os.environ; T = E["TARGET"]
cfg = {"server": json.load(open(os.path.join(T, "registration.json")))["server"], "spoolDir": "/data"}
cfg["accessLogs"] = [{"path": E["ACCESS_LOG"], "format": E["ACCESS_FMT"]}] if E["ACCESS_LOG"] else []
cfg["appLogs"] = [{"path": p} for p in E["APP_LOGS_JOINED"].split("\n") if p]
if E["DB_ENGINE"]:
    cfg["db"] = {"engine": E["DB_ENGINE"], "url": E["DB_URL"], "user": E["DB_USER"], "password": E["DB_PASS"]}
cfg["allowServerSqlChecks"] = False
names = {80: "HTTP", 443: "HTTPS", 3306: "MySQL", 5432: "PostgreSQL", 6379: "Redis", 9000: "PHP-FPM", 11211: "Memcached"}
listening = {int(p) for p in E["LISTEN"].split()}
disks = ["/host"] + (["/host" + E["APP_DIR"]] if E["APP_DIR"] not in ("", "/") else [])
cfg["host"] = {
    "disks": disks,
    "processes": [{"name": n, "match": m} for n, m in (l.split("|", 1) for l in E["PROCS_JOINED"].split("\n") if l)],
    "ports": [{"name": n, "port": p} for p, n in names.items() if p in listening],
}
cfg["localReceiver"] = {"enabled": True, "port": 7071}
# OpenTelemetry: keep every failed or slow trace and 5% of the rest, at most 30 a minute
cfg["otlp"] = {"enabled": True, "port": 4318, "sampleRatio": 0.05, "maxTracesPerMinute": 30}
path = os.path.join(T, "agent.json")
json.dump(cfg, open(path, "w"), indent=2); os.chmod(path, 0o600)
print(f"    wrote {path}: {len(cfg['accessLogs'])} access log, {len(cfg['appLogs'])} app log(s), "
      f"db={'yes' if 'db' in cfg else 'no'}, {len(cfg['host']['processes'])} processes, {len(cfg['host']['ports'])} ports")
PY
rm -f "$TARGET/registration.json"
[ -f "$TARGET/fyntune-agent.jar" ] || { echo "    ! fyntune-agent.jar missing"; exit 1; }

cd "$TARGET"
docker compose build -q
note "self-test:"
if ! docker compose run --rm agent --test; then
  echo; warn "fix the lines marked with a cross (edit $TARGET/agent.json), then: cd $TARGET && sudo docker compose up -d"
  exit 1
fi
docker compose up -d
note "agent running: cd $TARGET && sudo docker compose logs -f"

# ---------------------------------------------------------------------------------------------------------------
step "6/6 Done"
note "App:        $APP_NAME ($APP_CODE) on $MONITOR_URL"
note "Health:     ${HEALTH_URL:-not set - add one in Setup > Checks (it decides when the app is Down)}"
note "Agent:      $TARGET (agent.json, docker-compose.yml); backups of changed files in $BACKUP"
note "Updates:    sudo fyntune-update   (nightly: sudo fyntune-update --auto on)"
note "Check:      the app should show Up in the monitor within 2-3 minutes"
