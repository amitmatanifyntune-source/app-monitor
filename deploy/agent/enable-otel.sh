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
#   sudo ./enable-otel.sh --check                 # read-only: what is in place, what is missing
#
# Options: --service NAME (name shown in traces; default: this server's name)  --app-dir DIR  --sample RATIO
#          --java  --no-php  --no-agent  --agent-dir DIR (default /opt/fyntune-agent)  --disable
#          --no-composer (do not add the OpenTelemetry packages to the PHP app; print the command instead)
#          --upgrade-agent (also upgrade an agent that is already 0.2+ to the monitor's current one)
#          --upgrade-only (just upgrade the agent; nothing else changes. update-agent.sh does this)
#          --check (read-only: report what is in place and what is missing; changes nothing)
#          --app-dir can be given more than once (several apps on one server)
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
SERVICE=""; APP_DIRS=(); SAMPLE="${OTEL_SAMPLE:-1.0}"; JAVA=0; PHP=1; AGENT=1; DISABLE=0; COMPOSER=1; UPGRADE=0; UPGRADE_ONLY=0; CHECK=0
while [ $# -gt 0 ]; do
  case "$1" in
    --service) SERVICE="$2"; shift ;; --app-dir) APP_DIRS+=("${2%/}"); shift ;; --sample) SAMPLE="$2"; shift ;;
    --agent-dir) AGENT_DIR="$2"; shift ;; --java) JAVA=1; PHP=0 ;; --no-php) PHP=0 ;; --no-agent) AGENT=0 ;;
    --disable) DISABLE=1 ;; --check) CHECK=1 ;; --no-composer) COMPOSER=0 ;; --upgrade-agent) UPGRADE=1 ;;
    --upgrade-only) UPGRADE=1; UPGRADE_ONLY=1; PHP=0 ;; -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "Unknown option $1 (see --help)"; exit 1 ;;
  esac
  shift
done
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }
step() { echo; echo "==> $*"; }
note() { echo "    $*"; }
warn() { echo "    ! $*"; }
BACKUP="/var/backups/fyntune-agent/$(date +%Y%m%d-%H%M%S)-otel"
SERVICE="${SERVICE:-$(hostname -s | tr 'A-Z_.' 'a-z--')}"
PHPV=""; command -v php >/dev/null && PHPV="$(php -r 'echo PHP_MAJOR_VERSION.".".PHP_MINOR_VERSION;')"

# ------------------------------------------------------------------ PHP: which versions, which apps

# The PHP-FPM versions that are running (they serve the apps), else those installed, else the command-line PHP.
# The command-line PHP is often not the one serving the app (e.g. php 8.4 on the shell, php8.2-fpm for the site).
fpm_versions() {
  local v
  v="$(systemctl list-units --type=service --state=running --no-legend 'php*-fpm.service' 2>/dev/null | grep -oE 'php[0-9]+\.[0-9]+-fpm' | sed -E 's/php|-fpm//g' | sort -uV)"
  [ -z "$v" ] && v="$(ls -d /etc/php/*/fpm 2>/dev/null | cut -d/ -f4 | sort -uV)"
  [ -z "$v" ] && v="$PHPV"
  echo $v
}

# PHP apps on this server (folders with composer.json and vendor/): first those the web server serves (nginx root,
# Apache DocumentRoot, or their parent for Laravel's public/), then a search a few levels down.
find_php_apps() {
  {
    { grep -rhoE '^[[:space:]]*root[[:space:]]+[^;]+' /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null | awk '{print $2}'
      grep -rhoiE '^[[:space:]]*DocumentRoot[[:space:]]+[^[:space:]]+' /etc/apache2/sites-enabled 2>/dev/null | awk '{print $2}'; } |
      tr -d '";' | sort -u | while read -r r; do
        for x in "$r" "${r%/public}" "$(dirname "$r")"; do [ -f "$x/composer.json" ] && [ -d "$x/vendor" ] && readlink -f "$x"; done
      done
    find /var/www /srv /home /opt -maxdepth 5 -name composer.json -not -path '*/vendor/*' -not -path '*/node_modules/*' \
         -not -path '/opt/fyntune-agent/*' -not -path '/opt/app-monitor/*' 2>/dev/null | while read -r f; do
      d="$(dirname "$f")"; [ -d "$d/vendor" ] && readlink -f "$d"
    done
  } | awk '!seen[$0]++'
}

php_ext_loaded() { "php-fpm$1" -m 2>/dev/null | grep -qx opentelemetry; }

# The extension for one PHP version: the distribution's php<v>-opentelemetry package when there is one (the ondrej
# PPA has them), else pecl for the command-line PHP; enabled for PHP-FPM as well as the command line.
ensure_php_ext() {
  local v="$1"
  if php_ext_loaded "$v"; then note "PHP $v: opentelemetry extension loaded"; return 0; fi
  if apt-cache show "php$v-opentelemetry" >/dev/null 2>&1; then
    apt-get install -y "php$v-opentelemetry" >/dev/null 2>&1 && note "PHP $v: installed php$v-opentelemetry"
  elif [ "$v" = "$PHPV" ]; then
    apt-get install -y "php$v-dev" php-pear build-essential >/dev/null 2>&1
    printf '\n' | pecl install opentelemetry >/dev/null 2>&1 || pecl upgrade opentelemetry >/dev/null 2>&1 || true
  else
    warn "PHP $v serves an app but has no php$v-opentelemetry package, and pecl builds only for PHP $PHPV here."
    warn "  Build it: sudo apt install php$v-dev php-pear && sudo pecl -d php_suffix=$v install opentelemetry"
    return 1
  fi
  [ -f "/etc/php/$v/mods-available/opentelemetry.ini" ] || echo "extension=opentelemetry.so" > "/etc/php/$v/mods-available/opentelemetry.ini"
  phpenmod -v "$v" -s ALL opentelemetry 2>/dev/null || phpenmod -v "$v" opentelemetry
  if php_ext_loaded "$v"; then note "PHP $v: opentelemetry extension enabled for PHP-FPM"; return 0; fi
  warn "PHP $v: the extension did not load in PHP-FPM (check: php-fpm$v -m | grep opentelemetry)"; return 1
}

otel_lines() { grep -hc 'env\[OTEL_' /etc/php/"$1"/fpm/pool.d/*.conf 2>/dev/null | awk '{n+=$1} END {print n+0}'; }

# ------------------------------------------------------------------ --check: report only
if [ "$CHECK" = 1 ]; then
  ok() { echo "  ✓ $*"; }; bad() { echo "  ✗ $*"; }
  echo "OpenTelemetry on $(hostname)"
  if [ -f "$AGENT_DIR/agent.json" ]; then
    python3 -c 'import json,sys; o=json.load(open(sys.argv[1])).get("otlp") or {}; sys.exit(0 if o.get("enabled", True) is not False else 1)' "$AGENT_DIR/agent.json" \
      && ok "agent in $AGENT_DIR, OpenTelemetry on in agent.json" || bad "agent.json has otlp.enabled=false: run sudo ./enable-otel.sh"
  else bad "no agent in $AGENT_DIR (--agent-dir?)"; fi
  if ss -ltn 2>/dev/null | grep -q ':4318 '; then
    code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"resourceSpans":[]}' http://127.0.0.1:4318/v1/traces)"
    [ "$code" = 200 ] && ok "agent receives on 127.0.0.1:4318 (test export answered 200)" || bad "port 4318 is open but answered $code"
  else bad "nothing listens on 127.0.0.1:4318: agent older than 0.2.0, or otlp off (sudo fyntune-update, then this script)"; fi
  for v in $(fpm_versions); do
    php_ext_loaded "$v" && ok "PHP-FPM $v: extension loaded" || bad "PHP-FPM $v: extension missing (sudo ./enable-otel.sh --no-agent)"
    n="$(otel_lines "$v")"; [ "$n" -ge 5 ] && ok "PHP-FPM $v: $n OTEL_ settings in its pools" || bad "PHP-FPM $v: no OTEL_ settings in /etc/php/$v/fpm/pool.d (sudo ./enable-otel.sh --no-agent)"
  done
  mapfile -t apps < <( [ ${#APP_DIRS[@]} -gt 0 ] && printf '%s\n' "${APP_DIRS[@]}" || find_php_apps )
  [ ${#apps[@]} -eq 0 ] && bad "no PHP app found (composer.json + vendor/): pass --app-dir /path/to/app"
  for a in "${apps[@]}"; do
    [ -d "$a/vendor/open-telemetry/sdk" ] && ok "app $a: OpenTelemetry packages installed" || bad "app $a: no OpenTelemetry packages (sudo ./enable-otel.sh --no-agent --app-dir $a)"
  done
  echo "Traces appear in the monitor about a minute after real requests; Dependencies fills first."
  exit 0
fi
mkdir -p "$BACKUP"

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
  for v in $(fpm_versions); do
    POOLDIR="/etc/php/$v/fpm/pool.d"; [ -d "$POOLDIR" ] || continue
    mkdir -p "$BACKUP/php$v" && cp -a "$POOLDIR" "$BACKUP/php$v/"
    for f in "$POOLDIR"/*.conf; do sed -i '/; >>> fyntune-otel/,/; <<< fyntune-otel/d' "$f"; done
    "php-fpm$v" -t >/dev/null 2>&1 && systemctl reload "php$v-fpm" && note "PHP-FPM $v no longer sends traces"
  done
  note "(the extension and the apps' OpenTelemetry packages stay installed, idle)"
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

  else
    note "agent $CUR already supports OpenTelemetry"
  fi

  # newer Docker files or service settings from the kit, whether or not the jar changed
  FILES_CHANGED=0
  if [ "$MODE" = docker ] && [ -f "$KIT/Dockerfile" ] && [ "$KIT" != "$AGENT_DIR" ]; then
    # the kit's Dockerfile and compose file bring the lean JVM settings and the CPU/memory caps; .env stays
    for f in Dockerfile docker-compose.yml; do
      if [ -f "$KIT/$f" ] && ! cmp -s "$KIT/$f" "$AGENT_DIR/$f"; then
        cp -a "$AGENT_DIR/$f" "$BACKUP/" 2>/dev/null || true
        install -m 644 "$KIT/$f" "$AGENT_DIR/$f"; note "updated $f"; FILES_CHANGED=1
      fi
    done
  elif [ "$MODE" = systemd ]; then
    UNIT="$(systemctl show -p FragmentPath --value fyntune-agent)"
    if [ -n "$UNIT" ] && [ -f "$UNIT" ] && { ! grep -q UseSerialGC "$UNIT" || ! grep -q '^CPUQuota=' "$UNIT"; }; then
      cp -a "$UNIT" "$BACKUP/"
      LEAN="-Xms16m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError"
      if ! grep -q UseSerialGC "$UNIT"; then
        sed -i -E "s#^(ExecStart=[^ ]*java)( -Xmx[0-9]+[mMgG])? #\1 $LEAN #" "$UNIT" && note "service: lean JVM settings"
      fi
      if ! grep -q '^CPUQuota=' "$UNIT"; then
        sed -i '/^Restart=always/a Nice=10\nIOSchedulingClass=best-effort\nIOSchedulingPriority=7\nCPUQuota=50%\nMemoryMax=256M' "$UNIT" && note "service: low priority, capped at half a core and 256 MB"
      fi
      systemctl daemon-reload
      FILES_CHANGED=1
    fi
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

  if [ "$CHANGED" = 1 ] || [ "$JAR_CHANGED" = 1 ] || [ "$FILES_CHANGED" = 1 ] || { [ "$UPGRADE_ONLY" = 0 ] && ! ss -ltn 2>/dev/null | grep -q ':4318 '; }; then
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
  step "PHP: extension and PHP-FPM settings"
  VERSIONS="$(fpm_versions)"
  if [ -z "$VERSIONS" ]; then warn "no PHP found; skipped (use --java for a Java app)"
  else
    note "PHP-FPM serving apps: $VERSIONS (command line: ${PHPV:-none})"
    for v in $VERSIONS; do
      if [ "${v%%.*}" -lt 8 ]; then warn "PHP $v: OpenTelemetry needs PHP 8.0 or newer; skipped"; continue; fi
      ensure_php_ext "$v" || true
      POOLDIR="/etc/php/$v/fpm/pool.d"
      if [ ! -d "$POOLDIR" ]; then warn "no $POOLDIR (PHP $v not served by PHP-FPM?): set the OTEL_* variables for that runtime by hand"; continue; fi
      mkdir -p "$BACKUP/php$v" && cp -a "$POOLDIR" "$BACKUP/php$v/"
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
      if "php-fpm$v" -t >/dev/null 2>&1; then
        systemctl reload "php$v-fpm"
        n="$(otel_lines "$v")"
        [ "$n" -ge 5 ] && note "PHP-FPM $v: sends traces to 127.0.0.1:4318 as \"$SERVICE\" (sample $SAMPLE; $n settings in place)" \
                       || warn "PHP-FPM $v: the settings did not stay in its pool files; check $POOLDIR"
      else
        rm -rf "$POOLDIR" && cp -a "$BACKUP/php$v/pool.d" "$POOLDIR"
        warn "PHP-FPM $v: php-fpm -t failed with the new settings; pool config restored"
      fi
    done

    # which app(s) get the OpenTelemetry packages
    if [ ${#APP_DIRS[@]} -eq 0 ]; then
      mapfile -t FOUND < <(find_php_apps)
      if [ ${#FOUND[@]} -eq 1 ]; then
        APP_DIRS=("${FOUND[0]}"); note "app found: ${FOUND[0]}"
      elif [ ${#FOUND[@]} -gt 1 ] && [ -t 0 ]; then
        echo "    Several PHP apps on this server. Which ones get the OpenTelemetry packages?"
        for n in "${!FOUND[@]}"; do echo "      $((n + 1))) ${FOUND[$n]}"; done
        read -r -p "    Numbers separated by spaces, 'a' for all, Enter for none: " pick
        if [ "$pick" = a ]; then APP_DIRS=("${FOUND[@]}")
        else for n in $pick; do [ "$n" -ge 1 ] 2>/dev/null && [ "$n" -le ${#FOUND[@]} ] && APP_DIRS+=("${FOUND[$((n - 1))]}"); done; fi
      elif [ ${#FOUND[@]} -gt 1 ]; then
        warn "several PHP apps found and no one to ask; their packages were NOT added. Run again with --app-dir:"
        for a in "${FOUND[@]}"; do warn "  sudo $0 --no-agent --app-dir $a"; done
      else
        warn "no PHP app found (a folder with composer.json and vendor/). The packages were NOT added."
        warn "  Run again with the app's folder: sudo $0 --no-agent --app-dir /path/to/app"
      fi
    fi
    for APP_DIR in "${APP_DIRS[@]}"; do
      if [ ! -f "$APP_DIR/composer.json" ]; then warn "$APP_DIR has no composer.json; skipped"; continue; fi
      if [ -d "$APP_DIR/vendor/open-telemetry/sdk" ]; then note "$APP_DIR already has the OpenTelemetry packages"
      elif [ "$COMPOSER" = 1 ]; then install_otel_packages "$APP_DIR"
      else
        warn "$APP_DIR has no OpenTelemetry packages (left alone: --no-composer). Add them in the app's repository:"
        warn "  composer require $(otel_packages "$APP_DIR")"
      fi
      # Laravel with a cached config: rebuild it so nothing stale hides the new packages
      if [ -f "$APP_DIR/artisan" ] && [ -f "$APP_DIR/bootstrap/cache/config.php" ]; then
        (cd "$APP_DIR" && sudo -u "$(stat -c %U "$APP_DIR/artisan")" php artisan config:cache >/dev/null 2>&1) && note "$APP_DIR: Laravel config cache rebuilt" || true
      fi
    done
    # new code in vendor/: reload PHP-FPM so its opcache picks it up
    for v in $VERSIONS; do systemctl reload "php$v-fpm" 2>/dev/null || true; done
    echo
    note "check any time, changing nothing: sudo $0 --check"
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
