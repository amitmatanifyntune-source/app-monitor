#!/usr/bin/env bash
# Turns on OpenTelemetry (Dependencies and Traces tabs) on a client server where the agent is already installed,
# without running the whole setup again. Touches only: the agent's otlp setting, the PHP opentelemetry extension and
# PHP-FPM's OTEL_* variables. Web server, logs, database user and the monitor registration are left as they are.
#
#   sudo ./enable-otel.sh                         # PHP app: agent + extension + PHP-FPM
#   sudo ./enable-otel.sh --sample 0.25           # record a quarter of requests in PHP (default: all)
#   sudo ./enable-otel.sh --java                  # Java app: agent only, then prints the -javaagent flags
#   sudo ./enable-otel.sh --disable               # turn PHP tracing off again (agent untouched)
#
# Options: --service NAME (name shown in traces; default: this server's name)  --app-dir DIR  --sample RATIO
#          --java  --no-php  --no-agent  --agent-dir DIR (default /opt/fyntune-agent)  --disable
# The agent must be 0.2.0 or newer. If it is older, put a new fyntune-agent.jar (Help > Download agent) next to
# this script and it is installed for you.
set -euo pipefail

KIT="$(cd "$(dirname "$0")" && pwd)"
AGENT_DIR=/opt/fyntune-agent
SERVICE=""; APP_DIR=""; SAMPLE="${OTEL_SAMPLE:-1.0}"; JAVA=0; PHP=1; AGENT=1; DISABLE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --service) SERVICE="$2"; shift ;; --app-dir) APP_DIR="$2"; shift ;; --sample) SAMPLE="$2"; shift ;;
    --agent-dir) AGENT_DIR="$2"; shift ;; --java) JAVA=1; PHP=0 ;; --no-php) PHP=0 ;; --no-agent) AGENT=0 ;;
    --disable) DISABLE=1 ;; -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
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

# ---------------------------------------------------------------------------------------------------------------
if [ "$DISABLE" = 1 ]; then
  step "Turning PHP tracing off"
  [ -n "$PHPV" ] || { warn "php not found"; exit 0; }
  POOLDIR="/etc/php/$PHPV/fpm/pool.d"
  cp -a "$POOLDIR" "$BACKUP/php-fpm-pool.d"
  for f in "$POOLDIR"/*.conf; do sed -i '/; >>> fyntune-otel/,/; <<< fyntune-otel/d' "$f"; done
  "php-fpm$PHPV" -t >/dev/null 2>&1 && systemctl reload "php$PHPV-fpm" && note "PHP-FPM no longer sends traces (the extension stays installed, idle)"
  exit 0
fi

# ---------------------------------------------------------------------------------------------------------------
if [ "$AGENT" = 1 ]; then
  step "Agent: OpenTelemetry receiver on 127.0.0.1:4318"
  [ -f "$AGENT_DIR/agent.json" ] || { echo "    ! no agent in $AGENT_DIR (install it first with setup-client.sh, or pass --agent-dir)"; exit 1; }
  ver() { python3 -c 'import sys,zipfile
try:
    m = zipfile.ZipFile(sys.argv[1]).read("META-INF/MANIFEST.MF").decode()
    print(next((l.split(":",1)[1].strip() for l in m.splitlines() if l.startswith("Implementation-Version")), "0"))
except Exception: print("0")' "$1"; }
  newer() { python3 -c 'import sys
v = lambda s: tuple(int(x) for x in (s.split("-")[0].split(".") + ["0","0"])[:3] if x.isdigit())
sys.exit(0 if v(sys.argv[1]) >= v(sys.argv[2]) else 1)' "$1" "$2"; }
  CUR="$(ver "$AGENT_DIR/fyntune-agent.jar")"
  JAR_CHANGED=0
  if ! newer "$CUR" 0.2.0; then
    if [ -f "$KIT/fyntune-agent.jar" ] && newer "$(ver "$KIT/fyntune-agent.jar")" 0.2.0; then
      cp -a "$AGENT_DIR/fyntune-agent.jar" "$BACKUP/" 2>/dev/null || true
      install -m 644 "$KIT/fyntune-agent.jar" "$AGENT_DIR/fyntune-agent.jar"
      note "agent $CUR -> $(ver "$AGENT_DIR/fyntune-agent.jar")"
      JAR_CHANGED=1
    else
      echo "    ! the agent is $CUR; OpenTelemetry needs 0.2.0 or newer. Put the new fyntune-agent.jar (Help > Download agent) next to this script and run it again."
      exit 1
    fi
  else
    note "agent $CUR already supports OpenTelemetry"
  fi
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
  if [ "$CHANGED" = 1 ] || [ "$JAR_CHANGED" = 1 ] || ! ss -ltn 2>/dev/null | grep -q '127.0.0.1:4318 '; then
    if [ -f "$AGENT_DIR/docker-compose.yml" ]; then (cd "$AGENT_DIR" && docker compose up -d --build >/dev/null); note "agent container restarted"
    elif systemctl list-unit-files fyntune-agent.service >/dev/null 2>&1; then systemctl restart fyntune-agent; note "agent service restarted"
    else warn "could not tell how the agent runs; restart it yourself"; fi
  fi
  for _ in $(seq 1 15); do ss -ltn 2>/dev/null | grep -q ':4318 ' && break; sleep 2; done
  if ss -ltn 2>/dev/null | grep -q ':4318 '; then note "listening on 127.0.0.1:4318"
  else warn "nothing listens on 4318 yet: check the agent's log (another OpenTelemetry collector on that port?)"; fi
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
      warn "the app in $APP_DIR has no OpenTelemetry packages yet. The app team adds them in the app's repository and deploys:"
      warn "  composer require open-telemetry/sdk open-telemetry/exporter-otlp php-http/guzzle7-adapter \\"
      warn "    open-telemetry/opentelemetry-auto-laravel open-telemetry/opentelemetry-auto-pdo open-telemetry/opentelemetry-auto-guzzle"
      warn "  (plain PHP: leave out opentelemetry-auto-laravel). Traces start after that deploy."
    elif [ -n "$APP_DIR" ] && [ -d "$APP_DIR/vendor/open-telemetry/sdk" ]; then
      note "the app already has the OpenTelemetry packages: traces should appear within a minute or two of traffic"
    fi
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
