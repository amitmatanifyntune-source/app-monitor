# Fyntune Ops protocol, v1

How agents talk to the monitoring server, and how in-app libraries talk to the agent. All JSON over HTTPS. No third-party
component sits anywhere in this path.

```
in-app library ──POST http://127.0.0.1:7071/v1/events──► agent ──POST /ingest/v1/batch──► monitoring server
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

Reply: `200 {"accepted": true, "seq": 42}`, or `{"accepted": false, "seq": <last stored>}` for a duplicate.

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

## `POST http://127.0.0.1:7071/v1/events` (agent, local only)
In-app libraries (Laravel, Spring, browser gateway) send events here. The body is a JSON array of `events[]` objects as
above. Requests can also be sent so the agent counts them like access-log lines:

```json
[ { "type": "REQUEST", "method": "GET", "path": "/api/orders/{id}", "status": 200, "ms": 35, "requestId": "a1b2c3d4" },
  { "type": "ERROR", "source": "app", "requestId": "a1b2c3d4", "message": "SQLSTATE[23000] …", "detail": "#0 app/Http/…" } ]
```

The receiver listens only on `127.0.0.1`. Set `localReceiver.token` in `agent.json` to also require
`Authorization: Bearer <token>`.
