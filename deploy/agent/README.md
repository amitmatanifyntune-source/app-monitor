# Install the agent on an application server (Ubuntu 22.04 / 24.04)

## Fastest: one script does everything (Docker)
Copy this folder to the app server and run:

```bash
sudo ./setup-client.sh
```

It asks for the monitor URL, an app code, the health URL and the monitor admin password (used once, not stored), then:

1. adds the app in the monitor, registers this server's agent, downloads `fyntune-agent.jar` and adds the health check;
2. adds a separate timed access log to nginx or Apache (`fyntune-access.log`; existing logs and formats untouched;
   config is tested and restored if the test fails);
3. finds the Laravel/PHP app, `laravel.log`, PHP-FPM log, queue workers, processes and listening ports, and adds
   logrotate for `laravel.log`;
4. creates a read-only `fyntune_monitor` database user (MySQL/MariaDB or PostgreSQL) when local root access works;
5. installs Docker, writes `/opt/fyntune-agent/agent.json`, builds the agent image (Java 21 inside), runs the
   self-test and starts it.

No questions: `sudo MONITOR_URL=https://monitor.example.com APP_CODE=billing-portal HEALTH_URL=https://billing.example.com/up ./setup-client.sh --yes`
(password from `MONITOR_PASSWORD` or prompted). Options: `--no-db`, `--no-web-log`, `--laravel-single` (switch
Laravel's daily logs to one file), `--otel` (OpenTelemetry for PHP: installs the extension and points PHP-FPM at the agent
on `127.0.0.1:4318`; `OTEL_SAMPLE=0.25` records a quarter of requests). Running it again reuses the same agent and app. Every file it changes is copied to
`/var/backups/fyntune-agent/<date>/` first. No monitor password on hand? Put the `agent.json` downloaded from
Setup → Agents and `fyntune-agent.jar` next to the script; it then needs no sign-in (except to add the health check).

## Updating later: one command
```bash
sudo fyntune-update              # latest kit and agent from the monitor; settings stay
sudo fyntune-update --auto on    # every night at about 03:00 (randomised by up to an hour); --auto off to stop
```
The installers put this command in place. It fetches the kit and agent from the monitor this agent reports to, with
the agent's own id and key, verifies the checksums, keeps a backup of what it replaces under
`/var/backups/fyntune-agent/`, and never replaces `agent.json` or the Docker `.env`. The monitor must have been updated
first (`deploy/docker/update.sh`). On a server installed before this existed, run `sudo ./update.sh` once from a new
copy of this folder.

## Only OpenTelemetry, on a server that already has the agent
No need to run the whole setup again:

```bash
sudo ./enable-otel.sh                  # PHP: agent's otlp setting + PHP extension + PHP-FPM variables
sudo ./enable-otel.sh --sample 0.25    # record a quarter of PHP requests (default: all)
sudo ./enable-otel.sh --java           # Java: agent only, then prints the -javaagent flags to add
sudo ./enable-otel.sh --disable        # turn PHP tracing off again
sudo ./enable-otel.sh --check          # read-only: what is in place, what is missing
sudo ./enable-otel.sh --no-agent --app-dir /var/www/html/old/uat-jnm/backend   # a specific app
```

**Which app:** the script looks at the folders nginx (`root`) and Apache (`DocumentRoot`) serve, then searches
`/var/www`, `/srv`, `/home` and `/opt` a few levels down for PHP apps (`composer.json` plus `vendor/`). One app: it is
used. Several: you are asked which (or all); without a terminal it lists them and prints the `--app-dir` commands
instead of guessing. `--app-dir` can be given more than once.

**Which PHP:** every PHP-FPM version that is running gets the extension and the settings, not just the command-line
PHP (often 8.4 on the shell while the site runs on php8.2-fpm). The `php<v>-opentelemetry` package is used where the
distribution has it (the ondrej PPA does), `pecl` otherwise. After writing the settings it checks they are in place.

It touches only the agent's `otlp` setting (restarting the agent if needed), the PHP `opentelemetry` extension,
PHP-FPM's `OTEL_*` variables and the app's Composer packages; web server, logs, database user and the monitor
registration stay as they are.

**An older agent (for example 0.1.1) is upgraded in place**, no reinstall and no password: the script fetches the
monitor's current agent with this agent's own id and key from `agent.json` (checksum verified), keeps the old jar in the
backup folder, gives Docker installs the kit's newer `Dockerfile` and `docker-compose.yml` and systemd installs the lean
JVM settings and CPU/memory caps, restarts the agent and checks which version came up. The monitor must be upgraded
first (0.2+ hands out the agent); with an older monitor, put the new `fyntune-agent.jar` next to the script instead.

**Just upgrade the agent**, nothing else: `sudo ./update-agent.sh` (same as `enable-otel.sh --upgrade-only`).
`enable-otel.sh --upgrade-agent` also upgrades an agent that is already 0.2+.

**The app's OpenTelemetry packages** are added on the server for you (`--no-composer` to skip): the SDK, the OTLP
exporter, PDO instrumentation, plus Laravel, Guzzle or Symfony HTTP client instrumentation when the app uses them, and an
HTTP client for the exporter only if the app has none. Safeguards:

- a `composer require --dry-run` first: if the packages would conflict with the app's dependencies, nothing changes;
- only new packages are added, none of the app's own are upgraded; a `--no-dev` install stays without dev packages;
- runs as the owner of the app's files, not root;
- afterwards the app's autoloader (and `php artisan --version` for Laravel) must still load, or `composer.json`,
  `composer.lock` and `vendor/` are put back; queue workers are told to restart, PHP-FPM is reloaded.

**Commit `composer.json` and `composer.lock` to the app's repository afterwards.** A deploy from the repository replaces
the app's files, which would remove the packages again. Changed files are copied to
`/var/backups/fyntune-agent/<date>-otel/` first.

## Step by step instead
Two ways, both bring their own Java 21, so the application's Java (17, 11, none for PHP) is never changed.

| | Docker (`install-docker.sh`) | systemd (`install-systemd.sh`) |
|---|---|---|
| Java on the host | none: Java 21 is inside the image | `openjdk-21-jre-headless` installed next to existing Java; the system default `java` is kept |
| Best when | the server already runs Docker, or you want nothing on the host but the container | no Docker on the server, or the client does not allow it |
| Sees the host's processes, ports, DB | yes (`pid: host`, `network_mode: host`) | yes |
| Logs | read through read-only mounts (`/var/log`, `APP_DIR`) | read directly (user in `adm`, `www-data`) |
| Disks in `agent.json` | `"/host"`, `"/host/var/www"` (host `/` is mounted at `/host`) | `"/"`, `"/var/www"` |
| Upgrade | replace the jar, `docker compose up -d --build` | replace the jar in `/opt/fyntune-agent`, `systemctl restart fyntune-agent` |

## Steps (both ways)
1. In the App Monitor: add the app, then **Setup → Agents → Register** and download `agent.json`;
   **Help → Download agent** for `fyntune-agent.jar`.
2. Copy this folder to the server with those two files inside it:
   ```bash
   scp -r deploy/agent ubuntu@<server>:~/fyntune-agent      # then add agent.json and fyntune-agent.jar
   ```
3. Edit `agent.json`: access log, app logs, optional `db`, processes, ports (see `agent.docker.example.json` and
   `docs/onboarding-guide.md`). Set up the access-log format first (nginx `$request_time`, Apache/Tomcat `%D`).
4. Run one installer:
   ```bash
   cd ~/fyntune-agent
   sudo ./install-docker.sh /var/www      # Docker; argument = folder holding the app's logs
   # or
   sudo ./install-systemd.sh              # no Docker
   ```
   Both run the agent's `--test` first and stop if anything is marked ✗. The Docker installer also sets
   `spoolDir` to `/data` and turns disks into `/host…` paths for you.
5. In the App Monitor, add the health check and alerts; the app should show Up within 2–3 minutes.

## Docker details
- `network_mode: host` and `pid: host` let the container see the host's ports, `127.0.0.1` database and processes.
  No port is published; the agent only makes outbound HTTPS calls to the monitor.
- The container is read-only, drops all Linux capabilities except `DAC_READ_SEARCH` (read any log file, write none)
  and has `no-new-privileges`. Its only writable place is the `spool` volume.
- It is capped at half a CPU core and 192 MB (`cpus`, `mem_limit`); measured use is about 0.7% of a core and 80 MB
  while taking 200 OpenTelemetry spans a second. The systemd install runs at `Nice=10` with `CPUQuota=50%` and `MemoryMax=256M`.
- `-XX:-UseContainerSupport` makes Java report the host's CPU and memory instead of the container's.
- Logs outside `/var/log` and `APP_DIR` need one more line under `volumes:` (same path on both sides, `:ro`).
- Commands, in this folder: `sudo docker compose logs -f`, `sudo docker compose restart`,
  `sudo docker compose run --rm agent --test`, remove: `sudo docker compose down -v`.

## Java 17 on the server?
Not a problem with either installer: the agent runs on its own Java 21 and the application keeps its Java.
`install-systemd.sh` checks that installing Java 21 did not change the server's default `java` and puts it back if
it did.
