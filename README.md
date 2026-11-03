# Fyntune App Monitor

A self-hosted tool for monitoring any application, whatever it is written in: PHP/Laravel, Java, Node, .NET or Python,
with MySQL or Postgres behind it. It uses our own agent and protocol, with no third-party service or telemetry
anywhere in the data path. The whole tool can run inside a client's own network.

```
 client server                                              monitoring server (on-prem or ours)
 ┌─────────────────────────────────────────┐   HTTPS out   ┌──────────────────────────────┐
 │ app ── access log ─┐                    │──────────────►│ server/  Spring Boot + Postgres │
 │ app ── app log ────┤  fyntune-agent.jar │  batches,     │ ui/      React                 │
 │ database (read-only user) ─┤           │  once a minute │                               │
 │ in-app library ─ 127.0.0.1:7071 ─┘     │◄── settings ───│                               │
 └─────────────────────────────────────────┘               └──────────────────────────────┘
```

| Folder | What it is |
|---|---|
| `server/` | Monitoring server: ingest API, storage, views, users and audit. Port 8095. |
| `ui/` | Web screens. Dev server on port 5180; `npm run build` puts it inside the server. |
| `agent/` | `fyntune-agent.jar`: one file, runs on each app server (Java 21). |
| `samples/` | Example `agent.json` files. |
| `PROTOCOL.md` | Agent ↔ server and library ↔ agent JSON formats. |

Proton core's own Admin → Monitoring screen is unchanged. Core is just one of the apps this tool can watch.

## What it shows per app
- **Status:** Up, Degraded, Down or No data.
  - **Down:** a health check is failing, or no agent has reported for 3 minutes.
  - **Degraded:** server errors in the last 15 minutes, another check failing, or p95 over the slow threshold.
- **Overview:** key highlights; requests, errors and refusals per minute; p95 and average response time.
- **APIs:** every endpoint, learned from traffic with ids replaced (`/api/orders/{id}`). Shows requests, errors,
  refused, median, p95, max and share of time.
- **Database:** size, connections, cache hit, locks, queries running now, heaviest statements and largest tables.
  Postgres and MySQL.
- **Errors & issues:**
  - server errors, refusals and slow requests
  - error lines from app logs, with their stack trace
  - failed checks
  - trace by request id, joining the access-log line and the log's stack trace
- **Checks:** HTTP (status, time, text, SSL expiry) and SQL business checks, with history.
- **Servers:** CPU, memory, disks, processes and ports.
- **Setup** (administrators): agents and their keys, checks, masking and path rules.

## Run it locally

Needs JDK 21, Node 20 and PostgreSQL 16.

```sh
psql postgres -c "CREATE USER monitor WITH PASSWORD 'monitor';"
psql postgres -c "CREATE DATABASE app_monitor OWNER monitor;"
psql postgres -c "CREATE DATABASE app_monitor_test OWNER monitor;"   # for the server's tests

cd server && ./mvnw spring-boot:run            # http://localhost:8095 (dev profile; admin / Password@123)
cd ui && npm install && npm run dev            # http://localhost:5180
cd agent && ./mvnw package                     # agent/target/fyntune-agent.jar
```

Production: build the UI (`cd ui && npm run build`), then `cd server && ./mvnw package`, then run
`java -jar target/app-monitor-server.jar`. Set these environment variables:

| Variable | Value |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | the monitoring server's own Postgres |
| `APP_JWT_SECRET` | at least 32 random bytes, e.g. `openssl rand -base64 48` |
| `MONITOR_ADMIN_PASSWORD` | used once, to create the first administrator |

Put it behind HTTPS (nginx or a load balancer). The server refuses to start outside development without a JWT secret.

## Onboarding an application
A full guide with the architecture, security, per-stack setup, health checks, deployment patterns and
troubleshooting is in [docs/onboarding-guide.md](docs/onboarding-guide.md).

The same steps, per stack and with copy buttons, are in the app under **Help: install & track** (`/help`).
Administrators can also download `fyntune-agent.jar` there.

1. **Add the app.** In the UI: All apps → Add an application.
2. **Register an agent** in Setup → Agents, one per server. The key is shown once: download `agent.json`.
3. **Edit `agent.json`** for that server. See `samples/`. Fill in:
   - `accessLogs`
   - `appLogs`
   - `db` (optional)
   - `host`
4. **Check it.** Run `java -jar fyntune-agent.jar agent.json --test`. It checks the server connection, every log
   file (and that its lines are understood), the database and the checks.
5. **Run it as a service.** Use systemd, a Windows service or Docker, with `java -jar fyntune-agent.jar agent.json`.
   It needs outbound HTTPS to the monitoring server only.
6. **Add checks** in Setup → Checks. Include at least one HTTP **health** check, which decides when the app is Down.

### Access log formats
The agent needs each request's response time in the access log. Add it once:

**nginx** (`format: "nginx"`, seconds):
```nginx
log_format fyntune '$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent '
                   '"$http_referer" "$http_user_agent" $request_time $request_id';
access_log /var/log/nginx/access.log fyntune;
```

**Apache** (`format: "apache"`, `%D` microseconds):
```apache
LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\" %D %{X-Request-Id}o" fyntune
CustomLog ${APACHE_LOG_DIR}/access.log fyntune
```

**Tomcat / Spring Boot** (`format: "tomcat"`, `%D` microseconds in Tomcat 10.1+; set `"timeUnit": "ms"` for older Tomcat):
```properties
server.tomcat.accesslog.enabled=true
server.tomcat.accesslog.pattern=%h %l %u %t "%r" %s %b %D %{X-Request-Id}o
```

Anything else: use `format: "custom"` with a `regex` that has the named groups `method`, `path`, `status` and `time`,
and optionally `rid` and `ts`.

A request id at the end of the line (nginx `$request_id`, or the app's `X-Request-Id` response header) links the
request to the app-log error that it caused.

### Laravel + MySQL + React (example)
```json
"accessLogs": [ { "path": "/var/log/nginx/access.log", "format": "nginx" } ],
"appLogs":    [ { "path": "/var/www/app/storage/logs/laravel.log" } ],
"db": { "engine": "mysql", "url": "jdbc:mysql://127.0.0.1:3306/appdb", "user": "monitor", "password": "…" },
"host": { "disks": ["/"], "processes": [ { "name": "php-fpm", "match": "php-fpm" } ], "ports": [ { "name": "MySQL", "port": 3306 } ] }
```
If Laravel writes daily files (`laravel-2026-10-26.log`), point the agent at a fixed path. Either set
`LOG_CHANNEL=single`, or have logrotate rotate `laravel.log`.

### Database monitoring user
The probe reads only the database's own statistics. Give it a separate read-only user.

**MySQL 8:**
```sql
CREATE USER 'monitor'@'<agent host>' IDENTIFIED BY '<password>';
GRANT PROCESS ON *.* TO 'monitor'@'<agent host>';
GRANT SELECT ON performance_schema.* TO 'monitor'@'<agent host>';
-- only if you add SQL business checks, and only on the tables they read:
GRANT SELECT ON appdb.payments TO 'monitor'@'<agent host>';
```
Statement statistics need `performance_schema=ON` (the default). On MySQL 5.7 lock waits come from a status
counter, and some figures are missing.

**PostgreSQL:**
```sql
CREATE ROLE monitor LOGIN PASSWORD '<password>';
GRANT pg_monitor TO monitor;
GRANT CONNECT ON DATABASE appdb TO monitor;
-- only for SQL business checks:
GRANT USAGE ON SCHEMA public TO monitor;  GRANT SELECT ON payments TO monitor;
```
Statement statistics also need `pg_stat_statements` in `shared_preload_libraries`, plus
`CREATE EXTENSION pg_stat_statements`.

### In-app libraries (optional, later)
Any app can send events to the agent on `http://127.0.0.1:7071/v1/events`; see `PROTOCOL.md`. This adds things logs
don't show: the logged-in user, business refusals and exceptions with context. Small Laravel, Spring and browser
packages that do this are planned. The format is plain JSON, so a few lines of code in any language work today.

## Alerts (Slack and WhatsApp)
Set them up per app, in **Setup → Alerts**. A message goes out when the app becomes **Down** or **Degraded** and has
stayed that way for 45 seconds. While it stays Down, the message repeats every 30 minutes. One more message goes out
when it is **Up** again. No data is never alerted. **Pause** an app's alerts for planned work: what happens during
the pause is not sent afterwards. Each channel can take Down only, or Down and Degraded. **Send test** proves a
channel works. The last 20 messages, with any delivery error, are listed under it.

| Variable | Value |
|---|---|
| `APP_PUBLIC_URL` | the monitor's address, e.g. `https://monitor.fyntune.com`, for the link in each message |

**Slack:** in Slack, add the *Incoming Webhooks* app to a channel and paste its URL. Only `https://hooks.slack.com/`
addresses are accepted. The URL is kept on the server and only its last characters are shown again.

**WhatsApp** goes through Meta's WhatsApp Business Cloud API, from your own WhatsApp Business number:
1. In Meta Business Manager, create a **Utility** message template named `app_monitor_alert`, language English. Use
   this body:
   `App Monitor alert: {{1}}. Details: {{2}}. Open: {{3}}`
   The variables are the headline (e.g. "Payments (production) is Down"), up to three reasons, and the link.
   Wait for Meta to approve it.
2. Set these on the server, then restart it:

| Variable | Value |
|---|---|
| `WHATSAPP_TOKEN` | a permanent (system user) access token with `whatsapp_business_messaging` |
| `WHATSAPP_PHONE_NUMBER_ID` | the *Phone number ID* of the sending number (not the number itself) |
| `WHATSAPP_TEMPLATE` | template name, if not `app_monitor_alert` |
| `WHATSAPP_TEMPLATE_LANG` | template language code, if not `en` |
| `WHATSAPP_API` | Graph API base, default `https://graph.facebook.com/v22.0`; move it to the version your Meta app uses |

3. Add each recipient's mobile number, with its country code, in Setup → Alerts.

Alert messages carry the app's status reasons, which the agent has already masked. They leave the monitoring server
only for the Slack or WhatsApp channels configured here.

## Data protection
- **Nothing third-party.** Agents send only to the server URL in `agent.json`. The only outbound messages from the
  server are the alerts an administrator sets up (see Alerts).
- **Masked at source.** The agent masks emails, mobile numbers, PAN, Aadhaar, card numbers, passwords and tokens
  (plus any custom patterns) before sending. Query strings and request or response bodies are never read.
- **Database credentials stay on the client's server.** They live only in `agent.json` and are never sent.
- **SQL business checks** defined on the server run only on agents that allow them (`"allowServerSqlChecks": true`),
  on a read-only connection and transaction, as a single `SELECT`/`WITH`, with a 10 s timeout. Only the row count
  and up to 10 reference values come back.
- **Agent keys** are stored as SHA-256 hashes. A key can be revoked at any time. Optional mutual TLS is set up with
  `tls.keyStore` / `tls.trustStore` in `agent.json`.
- **Access per app.** Viewers see only the apps they are given. The audit log records sign-ins, app views and every
  change.
- **Retention:** events and check results 30 days, per-minute statistics 90 days, database and host samples 7 days
  (`app.retention.*`).

## Tests
```sh
cd agent && ./mvnw test     # log formats, path rules, masking, percentiles, rotation, spool replay, SQL check guard
cd server && ./mvnw test    # sign-in, apps, agents, ingest (no double count on resend, multi-agent merge), views, access
cd ui && npm run build
```

## Known limits (MVP)
- **MySQL probe:** checked against MySQL's documented system tables but not yet run against a live MySQL server.
  Run `--test` on the first MySQL install.
- **Alerts** go to Slack and WhatsApp only. Email is not built yet.
- **The agent needs Java 21** on the app server. A bundled runtime (jlink) or a Go build can replace this later
  without changing the protocol.
