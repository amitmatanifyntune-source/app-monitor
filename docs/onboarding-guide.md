# Fyntune App Monitor: Architecture and Onboarding Guide

Live, editable version: https://claude.ai/artifact/XqgRVM4tEVLhNJ3oubuKyi

## Overview

Any new application can be monitored by adding it in the App Monitor UI, putting one agent file (`fyntune-agent.jar`) on each of its servers, and adding one health check. No code change is needed in the application.

Fyntune App Monitor is our own self-hosted monitoring tool. It works for any stack (PHP/Laravel, Java/Spring Boot, Node, .NET, Python) with MySQL or Postgres. It uses our own agent and protocol, with no third-party service or telemetry in the data path, so it can run entirely inside a client's network.

For each application it shows:

- **Status:** Up, Degraded, Down or No data.
- **Overview:** requests, errors and refusals per minute; p95 and average response time.
- **APIs:** every endpoint learned from traffic, with ids replaced (`/api/orders/{id}`).
- **Database:** size, connections, cache hit, locks, running and heaviest queries, largest tables.
- **Errors and issues:** server errors, slow requests, app-log errors with stack traces, and trace by request id.
- **Checks:** HTTP and SQL business checks, with history.
- **Servers:** CPU, memory, disks, processes and ports.

This guide is for the engineer onboarding an application, whether on a developer machine, a UAT server or a client's production servers.

## Architecture

Each application server runs one agent that reads local files and statistics and pushes a batch to the monitoring server once a minute over outbound HTTPS. The server never connects into the application's network.

```
 Application server (client network)              Monitoring server :8095
 ┌────────────────────────────────────┐           ┌─────────────────────────────┐
 │ Access log (time + request id) ─┐  │  outbound │ Ingest API                  │
 │ App log (errors, traces) ───────┤  │   HTTPS,  │ /ingest/v1/batch, /config   │
 │ Database (read-only user) ──────┼► Agent ─────►│             │               │
 │ In-app library (opt., :7071) ───┤  │ 1 batch/min│ PostgreSQL store            │
 │ Host (CPU, disk, ports) ────────┘  │◄────────── │             │               │
 │                                    │  config   │ Status and web UI           │
 │ Agent = fyntune-agent.jar:         │  every    │ (per-app views, Setup, Help)│
 │ reads and masks, counts per        │  5 min    │             │               │
 │ minute, runs checks, spools if     │           │ Alerts (Slack, WhatsApp)    │
 │ the monitor is unreachable         │           │                             │
 └────────────────────────────────────┘           └─────────────────────────────┘
```

Everything on the left stays on the client's server; only masked, per-minute batches cross to the monitor.

| Component | Runs on | What it does | Port |
| --- | --- | --- | --- |
| Agent (`fyntune-agent.jar`, Java 21) | each application server | tails access and app logs, probes the database, runs checks, samples CPU, memory, disks, processes and ports; masks data, then sends | local receiver 7071 (127.0.0.1 only) |
| Monitoring server (Spring Boot) | monitor host, on-prem or ours | ingest API, storage, views, users, audit, alerts | 8095 |
| Monitor database (PostgreSQL 16) | monitor host | stores minutes, endpoints, events, checks, samples | 5432 |
| Web UI (React) | built into the server | screens per app; Setup and Help for administrators | served by 8095 (dev: 5180) |
| In-app library (optional) | inside the application | posts business events (user, refusals, exceptions) to the agent | calls 7071 |

**Data flow**

1. The application writes its access log (with response time and request id) and its app log as usual.
2. The agent reads new lines, turns each URL into a route pattern, and counts requests, errors, refusals and response-time buckets per minute.
3. Every minute it sends `POST /ingest/v1/batch` with a sequence number, so a resend is never counted twice. If the server is unreachable, batches wait in the agent's `spool` folder.
4. Every 5 minutes it pulls `GET /ingest/v1/config`: slow threshold, masking patterns, route rules and checks. Checks are therefore managed centrally in the UI.
5. The server merges batches from all agents of an app, works out status, and sends Slack or WhatsApp alerts when an app turns Down or Degraded.

One monitoring server watches many applications. One application can have many agents, one per server; histograms are added so p95 stays correct across servers.

## Security and data handling

Nothing leaves the client's server except masked counts and messages, sent only to the monitor URL in `agent.json`.

- **Outbound only.** The agent opens HTTPS to the monitor; no inbound port is opened on the app server. The local receiver listens on 127.0.0.1 only.
- **Agent keys.** Each agent has its own id and key, shown once at registration. The server stores only a SHA-256 hash, and a key can be revoked any time. Optional mutual TLS via `tls.keyStore` / `tls.trustStore`.
- **Masked at source.** Emails, mobile numbers, PAN, Aadhaar, card numbers, passwords and tokens (plus custom patterns) are masked before sending. Query strings and request or response bodies are never read.
- **Database credentials stay local.** They live only in `agent.json` (keep it `chmod 600`) and are never sent. Use a separate read-only monitoring user.
- **SQL business checks** run only where `"allowServerSqlChecks": true`, as one read-only `SELECT`/`WITH` with a 10 s timeout. Only a count and up to 10 reference values return.
- **Access per app.** Viewers see only apps assigned to them; the audit log records sign-ins, views and every change.
- **Retention:** events and check results 30 days, per-minute statistics 90 days, database and host samples 7 days.
- **Production server settings:** `APP_JWT_SECRET` (at least 32 random bytes), `MONITOR_ADMIN_PASSWORD` (first admin), `DB_URL` / `DB_USER` / `DB_PASSWORD`; put the server behind HTTPS (nginx or a load balancer).

## Prerequisites

Collect these before you start; each missing item usually means a blank screen later.

- [ ] Monitoring server running and reachable from the app server over HTTPS (local dev: `http://localhost:8095`)
- [ ] Administrator login to the App Monitor UI
- [ ] Java 21 on every app server that will run the agent
- [ ] Path of the access log on each server (nginx, Apache or Tomcat), and permission for the agent to read it
- [ ] Access log format includes response time (and ideally a request id); see Per-stack configuration
- [ ] Path of the application log(s) and what an error line looks like
- [ ] A health URL that only this application answers (not just "something on port 8080")
- [ ] Optional: a database superuser or DBA to create a read-only monitoring user
- [ ] Ports and process names to watch (API, worker, database, UI)
- [ ] A free local receiver port on the server if more than one agent runs there (7071, 7072, 7073 …)
- [ ] A unique app code: 2–40 lower-case letters, digits or hyphens, e.g. `customer-portal`

## Onboarding a new application, step by step

Seven steps take an application from nothing to Up with alerts; about 30 minutes per server once the prerequisites are ready. The same steps, with copy buttons per stack, are in the UI under **Help: install & track** (`/help`), where administrators can also download `fyntune-agent.jar`.

1. **Add the app.** UI: All apps → Add an application. Give the code, name, environment (development, UAT, production) and slow threshold (default 2000 ms).
2. **Register one agent per server.** Setup → Agents → Register. Set the server URL the agent will reach (for a remote monitor, its public HTTPS address). The key is shown once: download `agent.json` straight away.
3. **Prepare the application's logs.** Make sure the access log carries response time and request id (next section). This is the only change on the app side, and it is configuration, not code.
4. **Edit `agent.json`** on that server (templates in `samples/`):
   - `accessLogs`: path + `format` (`nginx`, `apache`, `tomcat` or `custom`)
   - `appLogs`: path + optional `errorPattern`
   - `db`: engine, JDBC URL, read-only user (optional)
   - `host`: disks, processes (`name` + `match` text from the command line), ports
   - `localReceiver.port`: unique per agent on the same machine
   - `allowServerSqlChecks`: `true` only if you will add SQL business checks
5. **Test it.** `java -jar fyntune-agent.jar agent.json --test`. It checks the server connection and key, every log file (and that its lines are understood), the database, each check, the processes and the ports. Fix every ✗ before going on.
6. **Run it as a service.** Linux: systemd unit (`samples/fyntune-agent.service`); Windows: a service; containers: a sidecar or host service. The command is `java -jar fyntune-agent.jar agent.json`. On a dev machine, start it from the app's start script.
7. **Add checks and alerts.** Setup → Checks: at least one HTTP **health** check, which decides Down. Setup → Alerts: Slack and/or WhatsApp recipients, Down only or Down and Degraded; use **Send test**.

**Done when:** the app shows Up within 2–3 minutes, the APIs tab lists endpoints after some traffic, and stopping the app turns it Down and sends an alert.

## Per-stack configuration

The agent needs each request's response time in the access log; add it once per web server, then point `accessLogs` at the file.

| Stack | `format` | Time unit | Where to set it |
| --- | --- | --- | --- |
| nginx (PHP/Laravel, React, Node behind nginx) | `nginx` | seconds (`$request_time`) | `log_format` in nginx.conf |
| Apache | `apache` | microseconds (`%D`) | `LogFormat` / `CustomLog` |
| Tomcat / Spring Boot | `tomcat` | microseconds (`%D`, Tomcat 10.1+); `"timeUnit": "ms"` for older | `server.tomcat.accesslog.*` properties or env vars |
| Anything else (.NET, Node, Python) | `custom` | from your regex | `regex` with named groups `method`, `path`, `status`, `time`; optional `rid`, `ts` |

**nginx**

```nginx
log_format fyntune '$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent '
                   '"$http_referer" "$http_user_agent" $request_time $request_id';
access_log /var/log/nginx/access.log fyntune;
```

**Apache**

```apache
LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\" %D %{X-Request-Id}o" fyntune
CustomLog ${APACHE_LOG_DIR}/access.log fyntune
```

**Spring Boot** (properties, or the same as environment variables in a start script, so the jar is unchanged)

```bash
SERVER_TOMCAT_ACCESSLOG_ENABLED=true
SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/var/log/myapp/access
SERVER_TOMCAT_ACCESSLOG_PREFIX=access SERVER_TOMCAT_ACCESSLOG_SUFFIX=.log
SERVER_TOMCAT_ACCESSLOG_ROTATE=false SERVER_TOMCAT_ACCESSLOG_BUFFERED=false
SERVER_TOMCAT_ACCESSLOG_PATTERN='%h %l %u %t "%r" %s %b %D %{X-Request-Id}o'
```

A request id at the end of each line links a failed request to the stack trace it caused in the app log.

**Application logs.** Point `appLogs` at a fixed file name. Laravel daily files (`laravel-2026-10-26.log`) need `LOG_CHANNEL=single` or logrotate. For Spring Boot logs use `"errorPattern": "^\\S+\\s+(ERROR|FATAL)\\b"`.

**Read-only database user, PostgreSQL**

```sql
CREATE ROLE monitor LOGIN PASSWORD '<password>';
GRANT pg_monitor TO monitor;
GRANT CONNECT ON DATABASE appdb TO monitor;
-- only for SQL business checks:
GRANT USAGE ON SCHEMA public TO monitor;  GRANT SELECT ON payments TO monitor;
```

Statement statistics also need `pg_stat_statements` in `shared_preload_libraries` and `CREATE EXTENSION pg_stat_statements`.

**Read-only database user, MySQL 8**

```sql
CREATE USER 'monitor'@'<agent host>' IDENTIFIED BY '<password>';
GRANT PROCESS ON *.* TO 'monitor'@'<agent host>';
GRANT SELECT ON performance_schema.* TO 'monitor'@'<agent host>';
-- only for SQL business checks, on the tables they read:
GRANT SELECT ON appdb.payments TO 'monitor'@'<agent host>';
```

MySQL needs `performance_schema=ON` (the default). The MySQL probe has not yet run against a live server, so run `--test` carefully on the first MySQL install.

**Example `agent.json` (Laravel + MySQL)**

```json
"accessLogs": [ { "path": "/var/log/nginx/access.log", "format": "nginx" } ],
"appLogs":    [ { "path": "/var/www/app/storage/logs/laravel.log" } ],
"db": { "engine": "mysql", "url": "jdbc:mysql://127.0.0.1:3306/appdb", "user": "monitor", "password": "…" },
"host": { "disks": ["/"], "processes": [ { "name": "php-fpm", "match": "php-fpm" } ],
          "ports": [ { "name": "MySQL", "port": 3306 } ] }
```

## Health checks

The health check decides whether the app is Down, so it must prove that *this* application answers, not just that something listens on the port.

| Kind | Use for | Config keys | Health? |
| --- | --- | --- | --- |
| HTTP | is the app up and fast; SSL expiry | `url`, `expectStatus` (200), `maxMs` (3000), `contains`, `sslWarnDays` (14) | mark one as health |
| SQL | business signals: failed payments, stuck jobs, queue backlog | `sql` (one SELECT/WITH), `refColumn`, `warnAbove` | usually not |

**Rules of thumb**

- Use `contains` with text unique to the app. A bare `/actuator/health` returning `{"status":"UP"}` looks the same for every Spring Boot app. If another service could ever answer on the same port, check a page only this app serves (for example its API description) and require one of its own route names in the response.
- Health checks run every 30 s; a check times out at three times `maxMs` (at least 5 s).
- Mark only one check per app as health. Other failing checks make the app Degraded, not Down.
- Add workers and background services as separate, non-health HTTP checks or as watched processes.
- SQL checks need `"allowServerSqlChecks": true` on the agent and SELECT grants on the tables they read.

**Status rules:** Down = the health check fails, or no agent has reported for 3 minutes. Degraded = server errors in the last 15 minutes, another check failing, or p95 over the slow threshold. No data = never reported (not alerted).

## Deployment patterns

Pick the pattern by where the application runs; the agent and its `agent.json` are the same in all of them.

| Pattern | Monitor server | Agent runs as | Notes |
| --- | --- | --- | --- |
| Developer machine | local, `http://localhost:8095` (dev profile) | background process started by the app's start script | each agent on the same machine needs its own receiver port (7071, 7072, 7073 …) |
| Single VM (UAT or production) | ours or client's, over HTTPS | systemd `fyntune-agent` in `/opt/fyntune-agent` | `samples/fyntune-agent.service`; Java 21 on the box |
| Docker Compose | ours or client's | systemd on the host, reading logs from mounted volumes | write access logs to a host volume, or run the agent as a sidecar container with the log volume mounted |
| Several app servers | one monitor | one agent per server, all on the same app | histograms merge, so p95 stays correct |
| Client on-prem | inside the client network | systemd or Windows service | no internet needed; only the client's network carries data |
| Our hosted monitor (AWS) | EC2 behind HTTPS (`deploy/aws/provision-monitor.sh`) | systemd on the client box | client server needs outbound HTTPS to the monitor only |

**Running the server in production:** `cd ui && npm run build`, then `cd server && ./mvnw package`, then `java -jar target/app-monitor-server.jar` with `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_JWT_SECRET`, `MONITOR_ADMIN_PASSWORD` and `APP_PUBLIC_URL`. It refuses to start outside development without a JWT secret.

## Worked example: a fresh application

A new application, `customer-portal`, goes live on one Linux VM: nginx in front, a Spring Boot API on port 8080, PostgreSQL on 5432, and a hosted monitor at `https://monitor.example.com`.

1. In the UI, add app `customer-portal`, environment `production`, slow threshold 2000 ms.
2. Register agent `portal-vm-1` with server URL `https://monitor.example.com`; download `agent.json`.
3. Add the `fyntune` `log_format` to nginx and reload it; make the API write to a fixed log file name.
4. Create the read-only `monitor` role in PostgreSQL and enable `pg_stat_statements`.
5. Copy `fyntune-agent.jar` and `agent.json` to `/opt/fyntune-agent`, `chmod 600 agent.json`, run `--test`.
6. Install `fyntune-agent.service`, then `systemctl enable --now fyntune-agent`.
7. Add checks: health = `https://portal.example.com/api/health` containing a word only this app returns; a SQL check for failed payments if the business needs one. Add Slack and WhatsApp alerts and send a test.

```json
{
  "server": { "url": "https://monitor.example.com", "agentId": "ag_…", "agentKey": "…" },
  "spoolDir": "./spool",
  "accessLogs": [ { "path": "/var/log/nginx/access.log", "format": "nginx" } ],
  "appLogs": [ { "path": "/var/log/customer-portal/app.log", "errorPattern": "^\\S+\\s+(ERROR|FATAL)\\b" } ],
  "db": { "engine": "postgres", "url": "jdbc:postgresql://127.0.0.1:5432/portal", "user": "monitor", "password": "…" },
  "allowServerSqlChecks": true,
  "host": {
    "disks": ["/"],
    "processes": [ { "name": "Portal API", "match": "customer-portal.jar" }, { "name": "nginx", "match": "nginx" } ],
    "ports": [ { "name": "API", "port": 8080 }, { "name": "Database", "port": 5432 } ]
  },
  "localReceiver": { "enabled": true, "port": 7071 }
}
```

For a second server of the same application, register another agent and repeat steps 5 and 6; both report into the same app.

## Troubleshooting and operations

Start every investigation with `java -jar fyntune-agent.jar agent.json --test` and the agent's own log.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| App shows No data | agent never reached the server | check `server.url`, outbound HTTPS, agent id and key; re-run `--test` |
| Down: "no agent for 3 minutes" | agent stopped, or server restarted while agent was down | restart the agent service; spooled batches replay automatically |
| Down: "Connection refused" or "No answer" on health | app not running, or port held by something else | start the app; make the health check app-specific with `contains` |
| Overview empty, checks fine | access log missing response time, wrong `format`, or no traffic yet | fix the log format; `--test` reports lines it cannot parse |
| APIs tab shows many near-identical routes | ids not replaced in some paths | add a route rule in Setup (e.g. `^/files/.+$` → `/files/{path}`) |
| Errors without stack traces | no request id in the access log, or `errorPattern` wrong | add `$request_id` / `X-Request-Id`; check the pattern against a real error line |
| Database tab empty | no `db` block, missing grants, or `pg_stat_statements` off | create the read-only user; enable the extension |
| Agent fails to start: port in use | two agents on one machine with the same receiver port | give each a unique `localReceiver.port` |
| Agent crashes in a sandbox on host stats | OS blocks system calls (seen on macOS sandboxes) | run the agent as a normal service, outside the sandbox |

**Routine operations**

- Rotate an agent key: Setup → Agents → Revoke, then register again and replace `agent.json`.
- Planned work: pause the app's alerts; events during the pause are not sent afterwards.
- Upgrades: replace `fyntune-agent.jar` and restart the service; the protocol is versioned (v1), so server and agents can be upgraded separately.
- Remove an app: delete it in Setup, then stop and remove its agents.
