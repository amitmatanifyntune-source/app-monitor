# Fyntune Ops protocol, v1

How agents talk to the monitoring server, and how in-app libraries talk to the agent. All JSON over HTTPS. No third-party
component sits anywhere in this path.

```
in-app library ──POST http://127.0.0.1:7071/v1/events──► agent ──POST /ingest/v1/batch──► monitoring server
OpenTelemetry SDK ─POST http://127.0.0.1:4318/v1/traces─►
                                                          agent ◄──GET /ingest/v1/config──
```

## Authentication
Each agent is registered on the server, which shows its key once. The agent sends:

| Header | Value |
|---|---|
| `X-Agent-Id` | the agent's id (from registration) |
| `X-Agent-Key` | the agent's secret key. The server stores only its SHA-256 hash. |

Use HTTPS in any shared environment. Optional mutual TLS: set `tls.keyStore` / `tls.trustStore` in `agent.json`.

## `POST /ingest/v1/batch`
The agent sends one batch a minute. Batches carry a `seq` that increases by one per batch. The server ignores a batch
whose `seq` is not above the last one it stored for the agent, so a resend after a lost reply never counts twice.

```json
{
  "v": 1,
  "seq": 42,
  "agentVersion": "0.1.0",
  "host": "app-server-1",
  "sentAt": 1761490000000,
  "minutes": [
    { "minute": 29358166, "requests": 120, "errors": 1, "refused": 4, "slow": 0,
      "totalMs": 9800, "maxMs": 1450, "hist": [30,40,20,10,10,5,3,2,0,0,0,0] }
  ],
  "endpoints": [
    { "minute": 29358166, "method": "GET", "pattern": "/api/orders/{id}", "requests": 50, "errors": 0,
      "refused": 1, "slow": 0, "totalMs": 2100, "maxMs": 300, "hist": [10,20,15,5,0,0,0,0,0,0,0,0] }
  ],
  "events": [
    { "at": 1761489990000, "type": "ERROR", "source": "access-log", "requestId": "a1b2c3d4", "method": "POST",
      "path": "/api/orders/{id}/pay", "status": 500, "ms": 230, "actor": null, "message": "HTTP 500", "detail": null }
  ],
  "checks": [
    { "name": "Health", "kind": "HTTP", "at": 1761489995000, "ok": true, "ms": 42, "value": null,
      "message": "200 in 42 ms", "refs": [] }
  ],
  "db":   { "at": 1761489995000, "engine": "postgres", "version": "16.4", "snapshot": { } },
  "hostStats": { "at": 1761489995000, "cores": 8, "cpu": 0.21, "load1": 1.9, "memUsedMb": 900, "memTotalMb": 4096,
            "disks": [ { "path": "/", "freeMb": 51200, "totalMb": 256000 } ],
            "processes": [ { "name": "php-fpm", "up": true } ] }
}
```

- `spec` (agent 0.1.1+, with the first batch and then hourly) says what the server is: `hostname`, `os`, `kernel`,
  `arch`, `cpuModel`, `cores`, `memTotalMb`, `swapTotalMb`, `uptimeSeconds`, `virtualization` (Linux: container or VM
  type), `timezone`, `java`, `addresses[]` and `disks[{path, type, device, totalMb}]`. The server keeps the latest one per
  agent. `db.snapshot.spec` does the same for the database: `host` and `port` (from the JDBC address only), `sameServer`,
  `product`, `versionFull`, `role`, `startedAt`, `database`, and `settings{}` (memory, connections, WAL or InnoDB, SSL,
  encoding, time zone). It is read hourly.
- `minute` is minutes since the Unix epoch (UTC). Only finished minutes are sent.
- `hist` counts response times in these buckets (upper bounds, ms): `10, 25, 50, 100, 200, 400, 800, 1600, 3200,
  6400, 12800, ∞`. Proton core's `OpsRecorder` uses the same buckets. The server adds histograms from several agents,
  so percentiles stay correct across servers.
- `events[].type` is one of these:
  - `ERROR`: 5xx, or an exception reported by the app
  - `REFUSED`: 4xx other than 401, 403 and 404 (validation or a business rule)
  - `SLOW`: over the slow threshold
  - `LOG_ERROR`: an error line in an app log file
  - `CHECK_FAIL`: a check that failed
  - `APP`: any other event an in-app library reports
- `checks[].kind` is `HTTP` or `SQL`. A SQL check sends only a count (`value`) and up to 10 reference values (`refs`),
  never rows.
- `db.snapshot` comes from the database's own statistics views. See `agent/.../DbProbe` for the fields per engine.
- The agent masks every text field before sending: emails, mobile numbers, PAN, Aadhaar, card numbers and any custom
  patterns. Query strings and request or response bodies are never sent.

### OpenTelemetry fields (agent 0.2+)
Present only when the agent received OpenTelemetry spans (see the OTLP section below).

```json
"deps": [
  { "minute": 29358166, "kind": "http", "target": "api.insurer.example", "operation": "POST /v2/claims/{id}",
    "calls": 3, "errors": 1, "totalMs": 4200, "maxMs": 1900, "hist": [0,0,0,0,0,0,0,3,0,0,0,0] }
],
"traces": [
  { "traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "at": 1761489990000, "name": "POST /claims/{id}/submit", "service": "claims-api",
    "durationMs": 2300, "error": true, "reason": "error", "status": 502, "requestId": "9f2c1ab07e3d", "spanCount": 3,
    "spans": [ { "id": "00f067aa0ba902b7", "name": "POST /claims/{id}/submit", "kind": 2, "startUs": 0, "durUs": 2300000 },
               { "id": "2222222222222222", "parent": "00f067aa0ba902b7", "name": "POST", "kind": 3, "startUs": 100000, "durUs": 1900000,
                 "error": true, "dep": "http", "target": "api.insurer.example", "attrs": { "url.full": "https://api.insurer.example/v2/claims/98765" } } ] }
]
```

- `deps[].kind` is `db`, `http`, `queue` or `other`. `operation` is a summary such as `SELECT policies` or `POST /v1/orders/{id}`.
- `traces[].reason` is `error`, `slow` (root over the slow threshold) or `sample`. Span `kind` is OpenTelemetry's SpanKind
  (1 internal, 2 server, 3 client, 4 producer, 5 consumer). Times are relative to the trace start, in microseconds.
- `hostStats.agent` is what the agent itself costs the server: `cpu` (share of the machine, 0–1), `rssMb`, `heapUsedMb`,
  `heapMaxMb`, `threads`, `uptimeSec`, `spoolKb`, `otlp` (receiver listening), and since the previous batch `spansReceived`,
  `spansDropped`, `tracesKept`, `tracesDropped`, `tracesPending`, `spansPending`.

Reply: `200 {"accepted": true, "seq": 42}`, or `{"accepted": false, "seq": <last stored>}` for a duplicate.

Compression: when `GET /ingest/v1/config` answers `"gzip": true` (server 0.2+), agents send the batch with
`Content-Encoding: gzip`. The server unpacks at most 64 MB. Older servers do not send the flag and get plain JSON.

## `GET /ingest/v1/config`
The agent pulls its remote settings every 5 minutes:

```json
{
  "slowMs": 2000,
  "mask": ["(?i)token=[^&\\s]+"],
  "routeRules": [ { "match": "^/files/.+$", "replace": "/files/{path}" } ],
  "checks": [
    { "name": "Health", "kind": "HTTP", "url": "https://app.example.com/health", "expectStatus": 200,
      "maxMs": 2000, "contains": null, "intervalSec": 60 },
    { "name": "Payments pending > 1 h", "kind": "SQL", "intervalSec": 300, "warnAbove": 0, "refColumn": "ref",
      "sql": "SELECT order_no AS ref FROM payments WHERE status = 'PENDING' AND created_at < now() - interval '1 hour'" }
  ]
}
```

SQL checks from the server run only when the agent's local `agent.json` sets `"allowServerSqlChecks": true`. The agent
runs them on a read-only connection with a 10 s timeout and accepts only `SELECT` or `WITH`. Database credentials are
only in the local `agent.json` and are never sent to the server.

## `GET /ingest/v1/agent.jar`
The monitor's current agent jar, for an agent's server to upgrade in place (`enable-otel.sh`, `update-agent.sh`). The
agent's own `X-Agent-Id` and `X-Agent-Key` authenticate it, so no administrator password is needed on the client's
server. Headers `X-Agent-Version` and `X-Agent-Sha256` give the version (from the jar's manifest) and checksum; the
scripts refuse a download whose checksum does not match. Each fetch is written to the audit log as `agent:<id>`.

## `GET /ingest/v1/agent-kit.zip`
The client install kit (the `deploy/agent` scripts, Dockerfile, compose file and README) as a flat zip, for
`fyntune-update` on an agent's server; same authentication as `agent.jar`. `X-Kit-Sha256` is the zip's checksum and
`X-Agent-Version` the agent it goes with. Agent configs, `.env` files and jars are never included.

## `POST http://127.0.0.1:7071/v1/events` (agent, local only)
In-app libraries (Laravel, Spring, browser gateway) send events here. The body is a JSON array of `events[]` objects as
above. Requests can also be sent so the agent counts them like access-log lines:

```json
[ { "type": "REQUEST", "method": "GET", "path": "/api/orders/{id}", "status": 200, "ms": 35, "requestId": "a1b2c3d4" },
  { "type": "ERROR", "source": "app", "requestId": "a1b2c3d4", "message": "SQLSTATE[23000] …", "detail": "#0 app/Http/…" } ]
```

The receiver listens only on `127.0.0.1`. Set `localReceiver.token` in `agent.json` to also require
`Authorization: Bearer <token>`.

## `POST http://127.0.0.1:4318/v1/traces` (agent, local only): OpenTelemetry
The agent is an OTLP/HTTP trace endpoint, so apps export with `OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318` and no
collector is needed. Both `application/x-protobuf` (the Java agent's default) and `application/json` are read, optionally
gzip. `/v1/metrics` and `/v1/logs` are accepted and ignored. The port and limits are in `agent.json`:

```json
"otlp": { "enabled": true, "port": 4318, "sampleRatio": 0.05, "maxTracesPerMinute": 30, "maxSpansPerMinute": 50000 }
```

- Every client or producer span (and any span with `db.system` or `messaging.system`) is counted in `deps` by kind, target and
  operation.
- When a trace's root span arrives and the trace will not be kept (not failed, not slow, not sampled, or over this
  minute's limit), its spans are let go at once and its later spans are ignored; at most 20,000 spans wait in memory.
- Spans are grouped by trace; a trace is complete 3 s after its last span once its root has arrived (30 s at most). Failed
  and slow traces are kept up to `maxTracesPerMinute`; others by `sampleRatio`, decided from the trace id (the same on every
  agent), up to a third of that limit.
- Before anything is sent: SQL loses its literal values (`'x'` and `42` become `?`), URLs lose their query strings, and every
  text field goes through the agent's masking. Only a fixed list of attributes is kept (HTTP method, route, status, host,
  database system and statement, messaging destination, exception type and message).
- Over `maxSpansPerMinute` spans are dropped and counted; the receiver always answers at once, so the app never waits.
