#!/usr/bin/env bash
# Turns on OpenTelemetry (Dependencies and Traces tabs) on a client server where the agent is already installed,
# without running the whole setup again. Touches only: the agent's otlp setting, the PHP opentelemetry extension,
# PHP-FPM's OTEL_* variables and the app's Composer packages. Web server, logs, database user and the monitor
# registration are left as they are.
#
#   sudo ./enable-otel.sh                         # PHP app: agent + extension + PHP-FPM + the app's Composer packages
#   sudo ./enable-otel.sh --sample 0.25           # record a quarter of requests in PHP (default: all)
#   sudo ./enable-otel.sh --java                  # Java app: agent only, then prints the -javaagent flags
#   sudo ./enable-otel.sh --disable               # turn PHP tracing off again (agent untouched)
#
# Options: --service NAME (name shown in traces; default: this server's name)  --app-dir DIR  --sample RATIO
#          --java  --no-php  --no-agent  --agent-dir DIR (default /opt/fyntune-agent)  --disable
#          --no-composer (do not add the OpenTelemetry packages to the PHP app; print the command instead)
#          --upgrade-agent (also upgrade an agent that is already 0.2+ to the monitor's current one)
#          --upgrade-only (just upgrade the agent; nothing else changes. update-agent.sh does this)
# For a PHP app it also adds the OpenTelemetry Composer packages to the app on this server: a dry run first (nothing
# changes if it would conflict), only new packages (none of the app's are upgraded), run as the app's owner, then a
# check that the app still loads; on any failure composer.json, composer.lock and vendor/ are put back. Commit the two
# files to the app's repository afterwards, or the next deploy removes the packages again.
# The agent must be 0.2.0 or newer. An older one is upgraded in place: the current agent is fetched from the monitor
# with this agent's own id and key (checksum verified; no password), or taken from a fyntune-agent.jar next to this
# script. Docker installs get the kit's newer Dockerfile and compose file; systemd installs get the lean JVM settings
# and CPU/memory caps. The old jar and files are kept in the backup folder.
set -euo pipefail

KIT="$(cd "$(dirname "$0")" && pwd)"
AGENT_DIR=/opt/fyntune-agent
SERVICE=""; APP_DIR=""; SAMPLE="${OTEL_SAMPLE:-1.0}"; JAVA=0; PHP=1; AGENT=1; DISABLE=0; COMPOSER=1; UPGRADE=0; UPGRADE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --service) SERVICE="$2"; shift ;; --app-dir) APP_DIR="$2"; shift ;; --sample) SAMPLE="$2"; shift ;;
    --agent-dir) AGENT_DIR="$2"; shift ;; --java) JAVA=1; PHP=0 ;; --no-php) PHP=0 ;; --no-agent) AGENT=0 ;;
    --disable) DISABLE=1 ;; --no-composer) COMPOSER=0 ;; --upgrade-agent) UPGRADE=1 ;;
    --upgrade-only) UPGRADE=1; UPGRADE_ONLY=1; PHP=0 ;; -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "Unknown option $1 (see --help)"; exit 1 ;;
  esac
  shift
done
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
step() { echo; echo "==> $*"; }
note() { echo "    $*"; }
warn() { echo "    ! $*"; }
BACKUP="/var/backups/fyntune-agent/$(date +%Y%m%d-%H%M%S)-otel"
mkdir -p "$BACKUP"
SERVICE="${SERVICE:-$(hostname -s | tr 'A-Z_.' 'a-z--')}"
PHPV=""; command -v php >/dev/null && PHPV="$(php -r 'echo PHP_MAJOR_VERSION.".".PHP_MINOR_VERSION;')"

# ------------------------------------------------------------------ the app's OpenTelemetry packages (PHP)

# The packages for this app: the SDK and exporter always; instrumentation for what the app uses.
otel_packages() {
  local dir="$1" lock="$1/composer.lock" pk="open-telemetry/sdk open-telemetry/exporter-otlp open-telemetry/opentelemetry-auto-pdo"
  has() { [ -f "$lock" ] && grep -q "\"name\": \"$1\"" "$lock"; }
  [ -f "$dir/artisan" ] && pk="$pk open-telemetry/opentelemetry-auto-laravel"
  if has guzzlehttp/guzzle; then pk="$pk open-telemetry/opentelemetry-auto-guzzle"; fi
  if has symfony/http-client; then pk="$pk open-telemetry/opentelemetry-auto-symfony-httpclient"; fi
  # the exporter needs a PSR-18 HTTP client and PSR-17 factories: reuse the app's own where it has them
  if has php-http/guzzle7-adapter || has symfony/http-client; then :
  elif has guzzlehttp/guzzle && grep -q '"version": "7' <(grep -A2 '"name": "guzzlehttp/guzzle"' "$lock"); then pk="$pk php-http/guzzle7-adapter"
  else pk="$pk symfony/http-client"; fi
  if has nyholm/psr7 || has guzzlehttp/psr7; then :; else pk="$pk nyholm/psr7"; fi
  echo "$pk"
}

install_otel_packages() {
  local dir="$1" owner pk out
  owner="$(stat -c %U "$dir/composer.json")"
  pk="$(otel_packages "$dir")"
  if ! command -v composer >/dev/null; then
    apt-get install -y composer unzip >/dev/null && note "composer installed" || { warn "composer could not be installed; add the packages by hand: composer require $pk"; return 0; }
  fi
  export COMPOSER_HOME="/tmp/fyntune-composer-$$" COMPOSER_MEMORY_LIMIT=-1 COMPOSER_NO_INTERACTION=1 COMPOSER_ALLOW_SUPERUSER=1
  # a production install without dev packages stays that way
  local nodev=""
  grep -q '"dev": false' "$dir/vendor/composer/installed.json" 2>/dev/null && nodev="--update-no-dev"
  mkdir -p "$COMPOSER_HOME" && chown "$owner" "$COMPOSER_HOME"
  run() { (cd "$dir" && sudo -u "$owner" -E -H composer "$@"); }
  note "checking that the OpenTelemetry packages fit the app ($pk)"
  if ! out="$(run require --dry-run --no-progress $nodev $pk 2>&1)"; then
    warn "these packages conflict with the app's current dependencies, so nothing was changed:"
    echo "$out" | grep -E "Problem|requires|conflict|but" | head -8 | sed 's/^/      /'
    warn "the app team adds them in the app's repository instead: composer require $pk"
    rm -rf "$COMPOSER_HOME"; return 0
  fi
  cp -a "$dir/composer.json" "$BACKUP/composer.json"; [ -f "$dir/composer.lock" ] && cp -a "$dir/composer.lock" "$BACKUP/composer.lock"
  local ok=1
  if run require --no-progress --optimize-autoloader $nodev $pk > "$BACKUP/composer-require.log" 2>&1; then
    # the app must still load: its autoloader, and artisan for Laravel
    sudo -u "$owner" php -r "require '$dir/vendor/autoload.php'; echo 'ok';" >/dev/null 2>&1 || ok=0
    [ -f "$dir/artisan" ] && { (cd "$dir" && sudo -u "$owner" php artisan --version >/dev/null 2>&1) || ok=0; }
  else ok=0; fi
  if [ "$ok" = 1 ]; then
    note "OpenTelemetry packages added to the app (log: $BACKUP/composer-require.log)"
    [ -f "$dir/artisan" ] && (cd "$dir" && sudo -u "$owner" php artisan queue:restart >/dev/null 2>&1 || true)
    warn "commit $dir/composer.json and composer.lock to the app's repository, or the next deploy removes the packages again"
  else
    warn "the app did not load cleanly after adding the packages: putting composer.json, composer.lock and vendor/ back"
    cp -a "$BACKUP/composer.json" "$dir/composer.json"; [ -f "$BACKUP/composer.lock" ] && cp -a "$BACKUP/composer.lock" "$dir/composer.lock"
    run install --no-progress --optimize-autoloader ${nodev:+--no-dev} >> "$BACKUP/composer-require.log" 2>&1 && note "app restored to its previous packages" \
      || warn "restoring vendor/ failed: run 'composer install' in $dir (log: $BACKUP/composer-require.log)"
  fi
  rm -rf "$COMPOSER_HOME"
}

# ---------------------------------------------------------------------------------------------------------------
if [ "$DISABLE" = 1 ]; then
  step "Turning PHP tracing off"
  [ -n "$PHPV" ] || { warn "php not found"; exit 0; }
  POOLDIR="/etc/php/$PHPV/fpm/pool.d"
  cp -a "$POOLDIR" "$BACKUP/php-fpm-pool.d"
  for f in "$POOLDIR"/*.conf; do sed -i '/; >>> fyntune-otel/,/; <<< fyntune-otel/d' "$f"; done
  "php-fpm$PHPV" -t >/dev/null 2>&1 && systemctl reload "php$PHPV-fpm" && note "PHP-FPM no longer sends traces (the extension and the app's OpenTelemetry packages stay installed, idle)"
  exit 0
fi

# ---------------------------------------------------------------------------------------------------------------
if [ "$AGENT" = 1 ]; then
  step "Agent"
  [ -f "$AGENT_DIR/agent.json" ] || { echo "    ! no agent in $AGENT_DIR (install it first with setup-client.sh, or pass --agent-dir)"; exit 1; }
  ver() { python3 -c 'import sys,zipfile
try:
    m = zipfile.ZipFile(sys.argv[1]).read("META-INF/MANIFEST.MF").decode()
    print(next((l.split(":",1)[1].strip() for l in m.splitlines() if l.startswith("Implementation-Version")), "0"))
except Exception: print("0")' "$1"; }
  newer() { python3 -c 'import sys
v = lambda s: tuple(int(x) for x in (s.split("-")[0].split(".") + ["0","0"])[:3] if x.isdigit())
sys.exit(0 if v(sys.argv[1]) >= v(sys.argv[2]) else 1)' "$1" "$2"; }
  MODE=other
  if [ -f "$AGENT_DIR/docker-compose.yml" ]; then MODE=docker
  elif systemctl cat fyntune-agent >/dev/null 2>&1; then MODE=systemd; fi
  CUR="$(ver "$AGENT_DIR/fyntune-agent.jar")"
  note "installed agent: $CUR ($MODE)"
  JAR_CHANGED=0

  # a newer agent: next to this script, or fetched from the monitor with this agent's own id and key
  if [ "$UPGRADE" = 1 ] || ! newer "$CUR" 0.2.0; then
    NEW=""; TMPJAR="$(mktemp /tmp/fyntune-agent.XXXXXX.jar)"
    if [ -f "$KIT/fyntune-agent.jar" ] && newer "$(ver "$KIT/fyntune-agent.jar")" 0.2.0; then
      cp "$KIT/fyntune-agent.jar" "$TMPJAR"; NEW="$(ver "$TMPJAR")"; note "using fyntune-agent.jar next to this script ($NEW)"
    else
      note "fetching the current agent from the monitor"
      if OUT="$(python3 - "$AGENT_DIR/agent.json" "$TMPJAR" <<'PY'
import hashlib, json, sys, urllib.error, urllib.request
s = json.load(open(sys.argv[1]))["server"]
req = urllib.request.Request(s["url"].rstrip("/") + "/ingest/v1/agent.jar",
                             headers={"X-Agent-Id": s["agentId"], "X-Agent-Key": s["agentKey"], "User-Agent": "fyntune-enable-otel"})
try:
    with urllib.request.urlopen(req, timeout=180) as r:
        data, want, ver, ctype = r.read(), r.headers.get("X-Agent-Sha256"), r.headers.get("X-Agent-Version", "?"), r.headers.get("Content-Type", "")
except urllib.error.HTTPError as e:
    print(f"the monitor answered HTTP {e.code}"); sys.exit(1)
except Exception as e:
    print(f"cannot reach the monitor: {type(e).__name__}: {e}"); sys.exit(1)
if "octet-stream" not in ctype or not want:
    print("the monitor is older than 0.2 and cannot hand out the agent yet"); sys.exit(1)
if hashlib.sha256(data).hexdigest() != want:
    print("download damaged (checksum mismatch)"); sys.exit(1)
open(sys.argv[2], "wb").write(data)
print(ver)
PY
)"; then NEW="$(ver "$TMPJAR")"; note "downloaded agent $NEW (checksum verified)"
      else warn "$OUT"; fi
    fi
    # only a valid 0.2+ agent that is newer than the installed one: never a downgrade
    if [ -n "$NEW" ] && newer "$NEW" 0.2.0 && [ "$CUR" != "$NEW" ] && newer "$NEW" "$CUR"; then
      cp -a "$AGENT_DIR/fyntune-agent.jar" "$BACKUP/" 2>/dev/null || true
      install -m 644 "$TMPJAR" "$AGENT_DIR/fyntune-agent.jar"
      JAR_CHANGED=1
      note "agent $CUR -> $NEW (old jar kept in $BACKUP)"
    elif [ -n "$NEW" ] && newer "$CUR" "$NEW"; then
      note "the agent is already current ($CUR; the monitor has $NEW)"
    elif ! newer "$CUR" 0.2.0; then
      rm -f "$TMPJAR"
      echo "    ! the agent is $CUR and OpenTelemetry needs 0.2.0 or newer, but no newer agent could be found."
      echo "      Upgrade the monitor server first (it then hands out the new agent), or put the new fyntune-agent.jar"
      echo "      (monitor: Help > Download agent) next to this script, and run it again."
      exit 1
    fi
    rm -f "$TMPJAR"

    if [ "$JAR_CHANGED" = 1 ]; then
      if [ "$MODE" = docker ] && [ -f "$KIT/Dockerfile" ] && [ "$KIT" != "$AGENT_DIR" ]; then
        # the kit's newer Dockerfile and compose file bring the lean JVM settings and the CPU/memory caps
        for f in Dockerfile docker-compose.yml; do
          [ -f "$KIT/$f" ] && ! cmp -s "$KIT/$f" "$AGENT_DIR/$f" && { cp -a "$AGENT_DIR/$f" "$BACKUP/" 2>/dev/null; install -m 644 "$KIT/$f" "$AGENT_DIR/$f"; note "updated $f"; }
        done
      elif [ "$MODE" = systemd ]; then
        UNIT="$(systemctl show -p FragmentPath --value fyntune-agent)"
        if [ -n "$UNIT" ] && [ -f "$UNIT" ]; then
          cp -a "$UNIT" "$BACKUP/"
          LEAN="-Xms16m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError"
          if ! grep -q UseSerialGC "$UNIT"; then
            sed -i -E "s#^(ExecStart=[^ ]*java)( -Xmx[0-9]+[mMgG])? #\1 $LEAN #" "$UNIT" && note "service: lean JVM settings"
          fi
          if ! grep -q '^CPUQuota=' "$UNIT"; then
            sed -i '/^Restart=always/a Nice=10\nIOSchedulingClass=best-effort\nIOSchedulingPriority=7\nCPUQuota=50%\nMemoryMax=256M' "$UNIT" && note "service: low priority, capped at half a core and 256 MB"
          fi
          systemctl daemon-reload
        fi
      fi
    fi
  else
    note "agent $CUR already supports OpenTelemetry"
  fi

  CHANGED=0
  if [ "$UPGRADE_ONLY" = 0 ]; then
    cp -a "$AGENT_DIR/agent.json" "$BACKUP/agent.json"
    CHANGED=$(python3 - "$AGENT_DIR/agent.json" <<'PY'
import json, sys
p = sys.argv[1]; c = json.load(open(p)); o = c.get("otlp") or {}
want = {"enabled": True, "port": o.get("port", 4318), "sampleRatio": o.get("sampleRatio", 0.05), "maxTracesPerMinute": o.get("maxTracesPerMinute", 30)}
if o.get("maxSpansPerMinute"): want["maxSpansPerMinute"] = o["maxSpansPerMinute"]
if o != want:
    c["otlp"] = want; json.dump(c, open(p, "w"), indent=2); print(1)
else: print(0)
PY
)
    [ "$CHANGED" = 1 ] && note "agent.json: otlp turned on (keeps failed and slow traces, 5% of the rest, at most 30 a minute)"
  fi

  if [ "$CHANGED" = 1 ] || [ "$JAR_CHANGED" = 1 ] || { [ "$UPGRADE_ONLY" = 0 ] && ! ss -ltn 2>/dev/null | grep -q ':4318 '; }; then
    case "$MODE" in
      docker) (cd "$AGENT_DIR" && docker compose up -d --build --force-recreate >/dev/null) && note "agent container rebuilt and restarted" ;;
      systemd) systemctl restart fyntune-agent && note "agent service restarted" ;;
      *) warn "could not tell how the agent runs; restart it yourself" ;;
    esac
    # it must come back up and say which version it is
    sleep 5
    case "$MODE" in
      docker) LOGS="$(cd "$AGENT_DIR" && docker compose logs --since 2m 2>/dev/null)" ;;
      systemd) LOGS="$(journalctl -u fyntune-agent --since '-2min' --no-pager 2>/dev/null; tail -n 20 "$AGENT_DIR/agent.log" 2>/dev/null)" ;;
      *) LOGS="" ;;
    esac
    RUNNING="$(echo "$LOGS" | grep -o 'Fyntune agent [0-9.]*' | tail -1)"
    [ -n "$RUNNING" ] && note "running: $RUNNING" || warn "the agent's start-up line was not found in its log yet; check it shortly"
  fi
  if [ "$UPGRADE_ONLY" = 0 ]; then
    for _ in $(seq 1 15); do ss -ltn 2>/dev/null | grep -q ':4318 ' && break; sleep 2; done
    if ss -ltn 2>/dev/null | grep -q ':4318 '; then note "OpenTelemetry receiver listening on 127.0.0.1:4318"
    else warn "nothing listens on 4318 yet: check the agent's log (another OpenTelemetry collector on that port?)"; fi
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
if [ "$PHP" = 1 ]; then
  step "PHP: opentelemetry extension and PHP-FPM settings"
  if [ -z "$PHPV" ]; then warn "php not found; skipped (use --java for a Java app)"
  elif [ "${PHPV%%.*}" -lt 8 ]; then warn "PHP $PHPV: OpenTelemetry needs PHP 8.0 or newer; skipped"
  else
    if php -m 2>/dev/null | grep -qx opentelemetry; then note "opentelemetry extension already loaded"
    else
      apt-get install -y "php$PHPV-dev" php-pear build-essential >/dev/null
      printf '\n' | pecl install opentelemetry >/dev/null 2>&1 || pecl upgrade opentelemetry >/dev/null 2>&1 || true
      echo "extension=opentelemetry.so" > "/etc/php/$PHPV/mods-available/opentelemetry.ini"
      phpenmod -v "$PHPV" opentelemetry
      php -m | grep -qx opentelemetry && note "opentelemetry extension installed for PHP $PHPV" || warn "the extension did not load; see: pecl install opentelemetry"
    fi
    POOLDIR="/etc/php/$PHPV/fpm/pool.d"
    if [ -d "$POOLDIR" ]; then
      cp -a "$POOLDIR" "$BACKUP/php-fpm-pool.d"
      for f in "$POOLDIR"/*.conf; do
        sed -i '/; >>> fyntune-otel/,/; <<< fyntune-otel/d' "$f"
        cat >> "$f" <<OTELENV
; >>> fyntune-otel (App Monitor: traces go to the agent on this server)
env[OTEL_PHP_AUTOLOAD_ENABLED] = true
env[OTEL_SERVICE_NAME] = $SERVICE
env[OTEL_TRACES_EXPORTER] = otlp
env[OTEL_EXPORTER_OTLP_PROTOCOL] = http/json
env[OTEL_EXPORTER_OTLP_ENDPOINT] = http://127.0.0.1:4318
env[OTEL_METRICS_EXPORTER] = none
env[OTEL_LOGS_EXPORTER] = none
env[OTEL_PROPAGATORS] = tracecontext
env[OTEL_TRACES_SAMPLER] = parentbased_traceidratio
env[OTEL_TRACES_SAMPLER_ARG] = $SAMPLE
; <<< fyntune-otel
OTELENV
      done
      if "php-fpm$PHPV" -t >/dev/null 2>&1; then systemctl reload "php$PHPV-fpm"; note "PHP-FPM sends traces to 127.0.0.1:4318 as \"$SERVICE\" (sample $SAMPLE)"
      else rm -rf "$POOLDIR" && cp -a "$BACKUP/php-fpm-pool.d" "$POOLDIR"; warn "php-fpm -t failed; pool config restored"; fi
    else warn "no $POOLDIR (not PHP-FPM?); set the OTEL_* variables for your PHP runtime by hand"; fi
    if [ -z "$APP_DIR" ]; then
      for d in /var/www/*/ /var/www/html/ /srv/*/; do [ -f "${d}composer.json" ] && { APP_DIR="${d%/}"; break; }; done
    fi
    if [ -n "$APP_DIR" ] && [ -f "$APP_DIR/composer.json" ] && [ ! -d "$APP_DIR/vendor/open-telemetry/sdk" ]; then
      if [ "$COMPOSER" = 1 ]; then install_otel_packages "$APP_DIR"
      else
        warn "the app in $APP_DIR has no OpenTelemetry packages yet (left alone: --no-composer). Add them in the app's repository:"
        warn "  composer require $(otel_packages "$APP_DIR")"
      fi
    elif [ -n "$APP_DIR" ] && [ -d "$APP_DIR/vendor/open-telemetry/sdk" ]; then
      note "the app already has the OpenTelemetry packages: traces should appear within a minute or two of traffic"
    fi
    # new code in vendor/: reload PHP-FPM so its opcache picks it up
    [ -n "$APP_DIR" ] && [ -d "$APP_DIR/vendor/open-telemetry/sdk" ] && systemctl reload "php$PHPV-fpm" 2>/dev/null || true
  fi
fi

# ---------------------------------------------------------------------------------------------------------------
if [ "$JAVA" = 1 ]; then
  step "Java: add the OpenTelemetry Java agent to how the app starts (no code change)"
  cat <<JAVAFLAGS
    1. Download opentelemetry-javaagent.jar (OpenTelemetry's GitHub releases) to /opt/otel/ on this server.
    2. Add to the app's start command (systemd ExecStart, JAVA_OPTS, or the container's command):

       -javaagent:/opt/otel/opentelemetry-javaagent.jar -Dotel.service.name=$SERVICE
       -Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318 -Dotel.exporter.otlp.protocol=http/protobuf
       -Dotel.metrics.exporter=none -Dotel.logs.exporter=none

       (an app in Docker: use the host's address instead of 127.0.0.1, or run it with network_mode: host)
    3. Restart the app. Traces appear in the monitor within a minute or two of traffic.
JAVAFLAGS
fi

echo
echo "Done. Changed files were copied to $BACKUP first. Turn PHP tracing off again with: sudo $0 --disable"
