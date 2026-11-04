import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, errorText, get } from '../api'
import { Pan } from '../ui'

/**
 * How to put an agent on an application's server, and how to read what it reports. Written for whoever onboards a
 * client app; every command and setting here matches what the agent and server actually do (see README.md).
 */

type Stack = 'laravel' | 'spring' | 'apache' | 'other'
const STACKS: [Stack, string][] = [['laravel', 'PHP / Laravel + nginx + MySQL'], ['spring', 'Java / Spring Boot + Postgres'],
  ['apache', 'PHP on Apache'], ['other', 'Node, .NET, Python, other']]

const SECTIONS: [string, string][] = [
  ['how', 'How it works'], ['before', 'Before you start'], ['add', '1 · Add the app'], ['register', '2 · Register an agent'],
  ['install', '3 · Install on the server'], ['test', '4 · Test the setup'], ['service', '5 · Run it as a service'],
  ['checks', '6 · Add checks'], ['otel', 'Traces and dependencies'], ['overhead', 'What the agent costs'], ['update', 'Updating'], ['track', 'Tracking the app'], ['investigate', 'Common investigations'],
  ['trouble', 'Troubleshooting'], ['data', 'Data and security'],
]

export default function HelpPage({ admin }: { admin: boolean }) {
  const [stack, setStack] = useState<Stack>('laravel')
  const { hash } = useLocation()
  // links such as /help#install open at that section
  useEffect(() => { if (hash) document.getElementById(hash.slice(1))?.scrollIntoView() }, [hash])
  return <>
    <div className="top"><div>
      <h2>Help: install an agent and track an app</h2>
      <div className="sub">From a new client application to seeing its traffic, errors, database and server here, in about 20 minutes.</div>
    </div></div>
    <div className="work help">
      <nav className="help-toc" aria-label="On this page">
        {SECTIONS.map(([id, t]) => <a key={id} href={`#${id}`}>{t}</a>)}
      </nav>
      <div className="help-body">

        <Section id="how" title="How it works">
          <p>One small program, the <b>agent</b>, runs on each server the application runs on. It does not change the application or its code. Every minute it
            sends a summary to this monitoring server over HTTPS. Nothing goes to any third party.</p>
          <pre className="code">{`  App server (client's network)                         This monitoring server
 ┌─────────────────────────────────────────┐            ┌─────────────────────────┐
 │ web server access log ──┐               │   HTTPS    │                         │
 │ app error log ──────────┤ fyntune-agent │ ─────────► │  stores and shows it    │
 │ database (read-only) ───┤   (Java 21)   │  outbound  │  here, per app          │
 │ CPU / memory / disk ────┘               │   only     │                         │
 └─────────────────────────────────────────┘ ◄───────── └─────────────────────────┘
                                               settings (checks, masking rules)`}</pre>
          <table className="help-t"><tbody>
            <tr><td><b>From the access log</b></td><td>Every API call: path, status, response time. Paths are grouped (<span className="mono">/api/orders/123</span> → <span className="mono">/api/orders/{'{id}'}</span>).</td></tr>
            <tr><td><b>From the app log</b></td><td>Error lines with their stack trace, linked to the request when the line carries a request id.</td></tr>
            <tr><td><b>From the database</b></td><td>Size, connections, cache hit, locks, running and heaviest queries, largest tables. Only the database's own statistics; never application data.</td></tr>
            <tr><td><b>Checks</b></td><td>Health URLs and key APIs called from inside the client's network; optional SQL business checks.</td></tr>
            <tr><td><b>The server</b></td><td>CPU, memory, disks, and whether the app's processes and ports are up.</td></tr>
          </tbody></table>
        </Section>

        <Section id="before" title="Before you start">
          <p>Agree these with the client's IT team first:</p>
          <ul className="check">
            <li><b>Java 21</b> on the app server (<span className="mono">java -version</span>). Any vendor: Temurin, Oracle, Amazon Corretto, the OS package.</li>
            <li><b>Outbound HTTPS</b> from the app server to this monitoring server. Nothing needs to be opened <i>into</i> the client's network.</li>
            <li><b>Read access to the log files</b> for the user the agent runs as (e.g. membership of the <span className="mono">adm</span> group for nginx logs).</li>
            <li>For database monitoring: a <b>read-only monitoring user</b>, created by their DBA (step 3 has the exact grants).</li>
            <li>About 150 MB of memory and 200 MB of disk for the agent (the disk is a buffer for when this server cannot be reached).</li>
          </ul>
        </Section>

        <Section id="add" title="1 · Add the app">
          <p>{admin ? <>Go to <Link to="/">All apps</Link> → <b>Add an application</b>.</> : <>An administrator adds the app under All apps → Add an application.</>} Give it a name
            (e.g. "Client X portal – production"), a code used in links, the environment, and the response time above which a request counts as slow (default 2000 ms).</p>
          <p>Add one app per environment: production and UAT are separate apps, so their figures never mix.</p>
        </Section>

        <Section id="register" title="2 · Register an agent">
          <p>Open the app → <b>Setup</b> → <b>1 · Agents</b>. Enter a name for the server (e.g. <span className="mono">web-1</span>) and the address the agent will use to reach this
            monitoring server, then <b>Register agent</b>.</p>
          <ul>
            <li>The agent's <b>key is shown only once</b>. Click <b>Download agent.json</b>: it already contains the server address, agent id and key.</li>
            <li>Treat agent.json like a password. If it leaks, <b>Revoke</b> the agent and register a new one.</li>
            <li>An app on several servers gets one agent per server; their figures are added together.</li>
          </ul>
          <AgentDownload admin={admin} />
        </Section>

        <Section id="install" title="3 · Install on the server">
          <div className="tabs">{STACKS.map(([k, l]) => <button key={k} className={`chip${stack === k ? ' on' : ''}`} onClick={() => setStack(k)}>{l}</button>)}</div>
          {stack === 'laravel' && <Laravel />}
          {stack === 'spring' && <Spring />}
          {stack === 'apache' && <Apache />}
          {stack === 'other' && <Other />}
          <h4>Copy the files</h4>
          <Code>{`sudo mkdir -p /opt/fyntune-agent
sudo cp fyntune-agent.jar agent.json /opt/fyntune-agent/
sudo chmod 600 /opt/fyntune-agent/agent.json      # it holds the agent key and the database password`}</Code>
          <h4>What each part of agent.json does</h4>
          <table className="help-t"><tbody>
            <tr><td className="mono">server</td><td>Monitoring server address, agent id and key. Filled in by Download agent.json.</td></tr>
            <tr><td className="mono">accessLogs</td><td>Web server access logs: <span className="mono">path</span> and <span className="mono">format</span> (nginx, apache, tomcat or custom).</td></tr>
            <tr><td className="mono">appLogs</td><td>The application's own log files. Error lines (ERROR, CRITICAL, FATAL, Exception…) and their stack traces are reported. Optional <span className="mono">errorPattern</span> regex.</td></tr>
            <tr><td className="mono">db</td><td>Optional. <span className="mono">engine</span> (mysql or postgres), JDBC <span className="mono">url</span>, the monitoring <span className="mono">user</span> and <span className="mono">password</span>. Stays on this server; never sent.</td></tr>
            <tr><td className="mono">allowServerSqlChecks</td><td>false by default. Set true only if the client agrees that SQL checks written here may run on their database (read-only).</td></tr>
            <tr><td className="mono">host</td><td><span className="mono">disks</span> to watch, <span className="mono">processes</span> that must be running (text or regex found in the command line), <span className="mono">ports</span> that must be open.</td></tr>
            <tr><td className="mono">tls</td><td>Optional: a trust store for a private certificate authority, and a key store for mutual TLS.</td></tr>
          </tbody></table>
        </Section>

        <Section id="test" title="4 · Test the setup">
          <Code>{`cd /opt/fyntune-agent
sudo -u fyntune-agent java -jar fyntune-agent.jar agent.json --test`}</Code>
          <p>It checks each part once and says what is wrong. A good result looks like:</p>
          <pre className="code">{`Fyntune agent 0.1.0 — checking https://monitor.example.com
  ✓ Server: reachable, agent accepted; 2 check(s) defined there
  ✓ Access log /var/log/nginx/access.log: 20/20 recent lines read; e.g. GET /api/orders/{id} → 200 in 42 ms
  ✓ App log /var/www/app/storage/logs/laravel.log: readable
  ✓ Database mysql 8.0.36: 1240 MB · 18 of 151 connections · cache hit 99.7% · ping 1 ms
  ✓ Check Health: 200 in 35 ms
  ✓ Host: Linux 5.15, 4 cores, memory 2900/7900 MB
  ✓ Process php-fpm: running
All good.`}</pre>
          <p>If the access-log line says <i>"in 0 ms (no response time in the log?)"</i>, the log format is missing the response time: see step 3 for your web server.</p>
        </Section>

        <Section id="service" title="5 · Run it as a service">
          <h4>Linux (systemd)</h4>
          <Code>{`sudo useradd --system --home /opt/fyntune-agent --shell /usr/sbin/nologin fyntune-agent
sudo usermod -aG adm fyntune-agent                 # read /var/log/nginx; use your log files' group
sudo chown -R fyntune-agent: /opt/fyntune-agent
sudo cp fyntune-agent.service /etc/systemd/system/  # samples/fyntune-agent.service in the repository
sudo systemctl daemon-reload && sudo systemctl enable --now fyntune-agent
journalctl -u fyntune-agent -f                     # its own log`}</Code>
          <h4>Windows</h4>
          <p>Run it as a service with a service wrapper the client already uses (e.g. NSSM or WinSW), with the command:</p>
          <Code>{`"C:\\Program Files\\Java\\jdk-21\\bin\\java.exe" -Xms16m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError -jar C:\\fyntune-agent\\fyntune-agent.jar C:\\fyntune-agent\\agent.json`}</Code>
          <h4>Docker</h4>
          <Code>{`docker run -d --name fyntune-agent --restart unless-stopped \\
  -v /opt/fyntune-agent:/opt/fyntune-agent \\
  -v /var/log/nginx:/var/log/nginx:ro -v /var/www/app/storage/logs:/var/www/app/storage/logs:ro \\
  --network host eclipse-temurin:21-jre \\
  java -Xms16m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError -jar /opt/fyntune-agent/fyntune-agent.jar /opt/fyntune-agent/agent.json`}</Code>
          <p>Within about a minute the agent shows <span className="badge good">reporting</span> in Setup → Agents, and the app's status changes from <i>No data yet</i>.</p>
        </Section>

        <Section id="checks" title="6 · Add checks">
          <p>In <b>Setup → 2 · Checks</b>. Agents pick up new or changed checks within 5 minutes.</p>
          <table className="help-t"><tbody>
            <tr><td><b>HTTP check</b></td><td>A URL called every interval from the app's own network. Fails on a wrong status, a slow answer, missing text, or an SSL certificate with
              less than 14 days left. <b>Add at least one health check</b> (tick "Health check"): when it fails, the app shows <span className="badge DOWN">Down</span>.</td></tr>
            <tr><td><b>SQL check</b></td><td>A business question as a SELECT where each row is one problem, e.g. payments pending over an hour. Fails when there are more rows than
              the limit. Only the count and up to 10 reference values come back. Runs only where agent.json allows it.</td></tr>
          </tbody></table>
          <p>Example SQL checks:</p>
          <Code>{`-- payments stuck in PENDING for over an hour (MySQL)
SELECT order_no AS ref FROM payments WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL 1 HOUR

-- jobs that failed today (Laravel queue)
SELECT id AS ref FROM failed_jobs WHERE failed_at > CURDATE()

-- policies issued without a document (Postgres)
SELECT policy_no AS ref FROM policy WHERE status = 'ISSUED' AND document_id IS NULL AND issued_at > now() - interval '1 day'`}</Code>
        </Section>

        <Section id="otel" title="Traces and dependencies (OpenTelemetry)">
          <p>Logs show each request from the outside. To see <b>inside</b> a request (which SQL queries ran, which external APIs were called and how long each took)
            the app sends OpenTelemetry traces to the agent on its own server. The agent listens on <span className="mono">127.0.0.1:4318</span> (standard OTLP over HTTP,
            protobuf or JSON); nothing is sent to any third party.</p>
          <table className="help-t"><tbody>
            <tr><td><b>Dependencies tab</b></td><td>Every database, external API and queue the app calls, with calls, errors, median, 95% within and share of time.</td></tr>
            <tr><td><b>Traces tab</b></td><td>Whole requests as a waterfall. The agent keeps every failed or slow request and a 5% sample of the rest, at most 30 a minute.
              SQL is kept without its values; query strings are removed; the usual masking applies.</td></tr>
          </tbody></table>
          <h4>PHP / Laravel</h4>
          <p>On a server that already has the agent, run only <span className="mono">sudo ./enable-otel.sh</span> from the agent kit (or <span className="mono">setup-client.sh --otel</span> on a new server).
            It turns on the agent's receiver, installs the PHP extension, sets PHP-FPM's variables and adds the app's OpenTelemetry Composer packages
            (dry run first, nothing upgraded, rolled back if the app no longer loads); <span className="mono">--disable</span> turns tracing off again.
            Then commit <span className="mono">composer.json</span> and <span className="mono">composer.lock</span> to the app's repository so the next deploy keeps them.
            An agent older than 0.2 is upgraded in place first, fetched from this server with its own key; <span className="mono">sudo ./update-agent.sh</span> upgrades the agent alone. By hand, the same is:</p>
          <Code>{`sudo pecl install opentelemetry            # PHP 8.0+, then enable extension=opentelemetry.so
composer require open-telemetry/sdk open-telemetry/exporter-otlp php-http/guzzle7-adapter \
  open-telemetry/opentelemetry-auto-laravel open-telemetry/opentelemetry-auto-pdo open-telemetry/opentelemetry-auto-guzzle

# PHP-FPM pool (e.g. /etc/php/8.3/fpm/pool.d/www.conf)
env[OTEL_PHP_AUTOLOAD_ENABLED] = true
env[OTEL_SERVICE_NAME] = billing-portal
env[OTEL_EXPORTER_OTLP_ENDPOINT] = http://127.0.0.1:4318
env[OTEL_EXPORTER_OTLP_PROTOCOL] = http/json
env[OTEL_METRICS_EXPORTER] = none
env[OTEL_LOGS_EXPORTER] = none
env[OTEL_TRACES_SAMPLER] = parentbased_traceidratio
env[OTEL_TRACES_SAMPLER_ARG] = 1.0        # 0.25 = record a quarter of requests`}</Code>
          <h4>Java / Spring Boot (no code change, Java 8 or newer)</h4>
          <p><span className="mono">sudo ./enable-otel.sh --java</span> turns on the agent's receiver and prints these flags for the app's start command:</p>
          <Code>{`java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  -Dotel.service.name=claims-api \
  -Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318 -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
  -jar app.jar`}</Code>
          <p>Node, .NET and Python: their OpenTelemetry auto-instrumentation with the same endpoint and protocol settings.</p>
          <h4>Limits in agent.json</h4>
          <Code>{`"otlp": { "enabled": true, "port": 4318, "sampleRatio": 0.05, "maxTracesPerMinute": 30, "maxSpansPerMinute": 50000 }`}</Code>
          <p>Over <span className="mono">maxSpansPerMinute</span> spans are dropped and counted, and the app is never made to wait. Two agents on one server: give one of them another port.</p>
        </Section>

        <Section id="overhead" title="What the agent costs the server">
          <p>Every agent reports its own CPU, memory, Java heap, threads and spool size, and how many OpenTelemetry spans it handled. They are shown on the
            <b> Servers</b> tab under <i>Monitoring agent overhead</i>, next to the app's figures, so the client can see the cost.</p>
          <table className="help-t"><tbody>
            <tr><td><b>Agent</b></td><td>Measured on a test machine: idle about 30–45 MB and near 0% CPU; while taking 200 OpenTelemetry spans a second, about 0.7% of one core and 80 MB at peak.
              It runs at low priority and is capped at half a core and 256 MB (192 MB in Docker). One gzip-compressed batch a minute, usually a few KB.</td></tr>
            <tr><td><b>Database</b></td><td>Connections, locks and running queries each minute; the heavier reads (size, top statements, largest tables) every 10 minutes, each with a 10 s timeout.</td></tr>
            <tr><td><b>OpenTelemetry in a PHP app</b></td><td>Roughly 1–5 ms per request and a few MB per PHP-FPM worker; more with many queries per request. Lower it with
              <span className="mono"> OTEL_TRACES_SAMPLER_ARG</span>; turn it off with <span className="mono">OTEL_SDK_DISABLED=true</span>.</td></tr>
            <tr><td><b>OpenTelemetry Java agent</b></td><td>A few seconds more at start-up, roughly 50–100 MB more memory and a few % CPU under load.</td></tr>
            <tr><td><b>Measure it</b></td><td>Load-test the same UAT build with and without tracing and compare the 95% within and PHP-FPM or JVM CPU. These ranges are typical, not promised.</td></tr>
          </tbody></table>
        </Section>

        <Section id="update" title="Updating, without reinstalling">
          <p>After a change is merged on GitHub, update the monitor once, then each client server, with one command each. Settings and data stay.</p>
          <h4>1 · This monitoring server</h4>
          <Code>{`cd app-monitor/deploy/docker && sudo ./update.sh`}</Code>
          <p>Backs up the database, pulls the latest code, builds while the current version keeps running, restarts, and checks it is healthy;
            if it is not, it goes back to the previous version by itself. The restart takes under a minute.</p>
          <h4>2 · Each client server</h4>
          <Code>{`sudo fyntune-update               # latest scripts, Docker files and agent from this monitor
sudo fyntune-update --auto on     # every night at about 03:00`}</Code>
          <p>The agent fetches them from here with its own key; nothing is needed from the code repository and no password is asked.
            <span className="mono"> agent.json</span> (logs, database, OpenTelemetry) is never replaced. Setup → Agents shows each agent's version after it reports.</p>
        </Section>

        <Section id="track" title="Tracking the app">
          <h4>Status</h4>
          <table className="help-t"><tbody>
            <tr><td><span className="badge UP">Up</span></td><td>Agents reporting, checks passing, no server errors in 15 minutes.</td></tr>
            <tr><td><span className="badge DEGRADED">Degraded</span></td><td>Server errors in the last 15 minutes, a non-health check failing, or 95% of requests slower than the app's slow threshold.</td></tr>
            <tr><td><span className="badge DOWN">Down</span></td><td>A health check is failing, or no agent has reported for 3 minutes (the server, the agent or the network is down).</td></tr>
            <tr><td><span className="badge NO_DATA">No data yet</span></td><td>No agent has reported since the app was added.</td></tr>
          </tbody></table>
          <h4>The tabs</h4>
          <table className="help-t"><tbody>
            <tr><td><b>Overview</b></td><td>Key highlights, most serious first; click one for its details. Requests per minute, response time (95% within, median), errors, refused and slow, over 1 hour to 7 days.</td></tr>
            <tr><td><b>APIs</b></td><td>Every endpoint seen: requests, errors, refused, median, 95% within, max, and its <i>share of time</i> (where the server spends its time: optimise the top rows first).</td></tr>
            <tr><td><b>Database</b></td><td>Connections against the limit, cache hit, locks waiting, queries running now (with how long), the heaviest statements, and tables with hints (missing index, vacuum behind, unused space).</td></tr>
            <tr><td><b>Errors &amp; issues</b></td><td>Server errors (5xx), refused requests (4xx other than 401/403/404: validation and business rules), slow requests, log errors with stack traces, failed checks. Grouped by place, and one by one; click a row for its detail.</td></tr>
            <tr><td><b>Checks</b></td><td>Each check now, its last 60 runs as green and red bars, and failures in 24 hours.</td></tr>
            <tr><td><b>Servers</b></td><td>Per agent: CPU, memory and disks with a 2-hour chart, and the processes and ports you listed.</td></tr>
            <tr><td><b>Setup</b></td><td>Agents and when each last reported; checks; masking and path rules (administrators).</td></tr>
          </tbody></table>
          <h4>Trace a request</h4>
          <p>When a user reports an error with a reference, or you have a request id from an <span className="mono">X-Request-Id</span> header, open <b>Errors &amp; issues</b> → <b>Trace a
            request</b>. You see the failed request from the access log and the error with its stack trace from the app log together. This needs the request id in both logs (step 3).</p>
        </Section>

        <Section id="investigate" title="Common investigations">
          <table className="help-t"><tbody>
            <tr><td><b>"The app is slow"</b></td><td>Overview → is 95% within up? APIs → sort by <i>95% within</i> and <i>Share of time</i> to find the endpoint. Database → heaviest statements
              and queries running now; locks waiting. Servers → CPU or memory at the limit?</td></tr>
            <tr><td><b>"Users see errors"</b></td><td>Errors &amp; issues → Server errors, grouped: which endpoint, how many, latest message. Click through to the log error and its stack trace.</td></tr>
            <tr><td><b>"The app is down"</b></td><td>Checks → which health check fails and its message (connection refused = app not running; timeout = hung or network). Setup → Agents: is the agent itself silent (server down)? Servers → processes not running.</td></tr>
            <tr><td><b>"Payments / orders are stuck"</b></td><td>A SQL check for it shows the count and the first 10 references; its history shows since when.</td></tr>
            <tr><td><b>"Many validation failures"</b></td><td>Errors &amp; issues → Refused: the endpoint and count tell you whether a client, a partner integration or a form is sending bad data.</td></tr>
          </tbody></table>
        </Section>

        <Section id="trouble" title="Troubleshooting">
          <table className="help-t"><tbody>
            <tr><td><b>Agent never reports</b></td><td>Run <span className="mono">--test</span>. "Cannot fetch settings" means the server address, or the agent id/key, is wrong, or the firewall blocks outbound HTTPS. A 401 in its log means the agent was revoked or the key is wrong.</td></tr>
            <tr><td><b>Agent "silent" after working</b></td><td>The server, the service or the network stopped. The agent keeps up to 200 MB of batches on disk while this server cannot be reached, and sends them when it can.</td></tr>
            <tr><td><b>No APIs, or every time is 0 ms</b></td><td>The access log path is wrong, unreadable by the agent's user, or its format has no response time. Fix the web server's log format (step 3).</td></tr>
            <tr><td><b>Paths not grouped</b> (one row per order number)</td><td>Add a path rule in Setup → App settings, e.g. <span className="mono">^/invoice/[A-Z0-9]+$ =&gt; /invoice/{'{no}'}</span>.</td></tr>
            <tr><td><b>Database "not reachable"</b></td><td>The JDBC URL, user or password; or the monitoring user lacks the grants in step 3; or the database only accepts connections from other hosts.</td></tr>
            <tr><td><b>"Statement statistics are not available"</b></td><td>MySQL: performance_schema off, or no SELECT on it. Postgres: pg_stat_statements not loaded.</td></tr>
            <tr><td><b>SQL checks never run</b></td><td>agent.json has <span className="mono">"allowServerSqlChecks": false</span> (the default), or no <span className="mono">db</span> section.</td></tr>
            <tr><td><b>Log files rotate daily</b></td><td>Point the agent at a fixed name (e.g. Laravel <span className="mono">LOG_CHANNEL=single</span> with logrotate). Rotation of a fixed name is followed automatically.</td></tr>
          </tbody></table>
        </Section>

        <Section id="data" title="Data and security">
          <ul>
            <li><b>No third party.</b> The agent sends only to the server address in agent.json. This monitoring server can run inside the client's own network.</li>
            <li><b>Masked before it leaves the server:</b> emails, mobile numbers, PAN, Aadhaar, card numbers, passwords and tokens, and any extra patterns in Setup → App settings.
              Query strings and request or response bodies are never read.</li>
            <li><b>Database credentials stay with the client</b>, in agent.json on their server. The database user is read-only and reads statistics only.</li>
            <li><b>Outbound only</b>, over HTTPS; optional mutual TLS. Agent keys are stored here only as a hash and can be revoked at any time.</li>
            <li><b>Access by app:</b> viewers see only the apps an administrator gives them. Sign-ins, app views and every change are in the Audit log.</li>
            <li><b>Kept for</b> 30 days (events, check results), 90 days (per-minute figures, dependencies), 7 days (database and server samples, traces).</li>
            <li><b>Traces:</b> SQL keeps no literal values, URLs lose their query strings, and every text is masked before it leaves the app server.</li>
          </ul>
        </Section>
      </div>
    </div>
  </>
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return <section id={id} className="help-s"><Pan title={title}><div className="help-text">{children}</div></Pan></section>
}

function Code({ children }: { children: string }) {
  const [copied, setCopied] = useState(false)
  return <div className="help-code">
    <button className="btn sm" onClick={() => { navigator.clipboard?.writeText(children).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) }) }}>
      {copied ? 'Copied' : 'Copy'}</button>
    <pre className="code">{children}</pre>
  </div>
}

function AgentDownload({ admin }: { admin: boolean }) {
  const info = useQuery({ queryKey: ['agent-info'], queryFn: () => get<{ available: boolean; sizeBytes: number; lastModified: number }>('/admin/agent/info'), enabled: admin })
  const [error, setError] = useState<string | null>(null)
  if (!admin) return <p className="small muted">An administrator can download the agent (fyntune-agent.jar) from this page.</p>
  async function download() {
    setError(null)
    try {
      const r = await api.get('/admin/agent/download', { responseType: 'blob' })
      const a = document.createElement('a')
      a.href = URL.createObjectURL(r.data as Blob)
      a.download = 'fyntune-agent.jar'
      a.click()
      URL.revokeObjectURL(a.href)
    } catch (e) { setError(errorText(e)) }
  }
  return <div className="row" style={{ marginTop: 8 }}>
    <button className="btn pri" disabled={!info.data?.available} onClick={download}>Download fyntune-agent.jar</button>
    <span className="small muted">{info.data?.available
      ? `${(info.data.sizeBytes / 1048576).toFixed(1)} MB · built ${new Date(info.data.lastModified).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })} · one file for every server`
      : 'Not built on this server yet: cd agent && ./mvnw package'}</span>
    {error && <span className="small" style={{ color: 'var(--red)' }}>{error}</span>}
  </div>
}

function Laravel() {
  return <>
    <h4>nginx: add the response time to the access log</h4>
    <p>In <span className="mono">/etc/nginx/nginx.conf</span> (http block), then <span className="mono">sudo nginx -t &amp;&amp; sudo systemctl reload nginx</span>:</p>
    <Code>{`log_format fyntune '$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent '
                   '"$http_referer" "$http_user_agent" $request_time $request_id';
access_log /var/log/nginx/access.log fyntune;`}</Code>
    <p>To link errors to requests, pass nginx's id to PHP (<span className="mono">fastcgi_param HTTP_X_REQUEST_ID $request_id;</span>) and include it in Laravel's log context.</p>
    <h4>MySQL: a read-only monitoring user (the client's DBA runs this)</h4>
    <Code>{`CREATE USER 'monitor'@'127.0.0.1' IDENTIFIED BY '<a strong password>';
GRANT PROCESS ON *.* TO 'monitor'@'127.0.0.1';
GRANT SELECT ON performance_schema.* TO 'monitor'@'127.0.0.1';
-- only for SQL business checks, and only the tables they read:
-- GRANT SELECT ON appdb.payments TO 'monitor'@'127.0.0.1';`}</Code>
    <h4>agent.json (edit the downloaded one)</h4>
    <Code>{`"accessLogs": [ { "path": "/var/log/nginx/access.log", "format": "nginx" } ],
"appLogs":    [ { "path": "/var/www/app/storage/logs/laravel.log" } ],
"db": { "engine": "mysql", "url": "jdbc:mysql://127.0.0.1:3306/appdb", "user": "monitor", "password": "…" },
"host": { "disks": ["/", "/var/www"],
          "processes": [ { "name": "php-fpm", "match": "php-fpm" }, { "name": "queue worker", "match": "artisan queue:work" } ],
          "ports": [ { "name": "MySQL", "port": 3306 } ] }`}</Code>
    <p className="small muted">Full example: samples/laravel-mysql.json. If Laravel writes daily files, set <span className="mono">LOG_CHANNEL=single</span> so the path stays fixed.</p>
  </>
}

function Spring() {
  return <>
    <h4>Spring Boot: turn on the access log</h4>
    <p>In <span className="mono">application.properties</span>, or as environment variables (SERVER_TOMCAT_ACCESSLOG_ENABLED=true and so on) without changing the app:</p>
    <Code>{`server.tomcat.accesslog.enabled=true
server.tomcat.accesslog.directory=/opt/app/logs
server.tomcat.accesslog.prefix=access
server.tomcat.accesslog.suffix=.log
server.tomcat.accesslog.file-date-format=
server.tomcat.accesslog.pattern=%h %l %u %t "%r" %s %b %D %{X-Request-Id}o`}</Code>
    <p><span className="mono">%D</span> is in microseconds on Tomcat 10.1+ (Spring Boot 3). On older Tomcat add <span className="mono">"timeUnit": "ms"</span> to the access log entry.
      <span className="mono"> %{'{X-Request-Id}'}o</span> logs the app's request id, if it sets that header.</p>
    <h4>Postgres: a read-only monitoring user</h4>
    <Code>{`CREATE ROLE monitor LOGIN PASSWORD '<a strong password>';
GRANT pg_monitor TO monitor;
GRANT CONNECT ON DATABASE appdb TO monitor;
-- only for SQL business checks:
-- GRANT USAGE ON SCHEMA public TO monitor; GRANT SELECT ON payments TO monitor;`}</Code>
    <p>Heaviest statements need <span className="mono">pg_stat_statements</span> in <span className="mono">shared_preload_libraries</span> and <span className="mono">CREATE EXTENSION pg_stat_statements</span>.</p>
    <h4>agent.json</h4>
    <Code>{`"accessLogs": [ { "path": "/opt/app/logs/access.log", "format": "tomcat" } ],
"appLogs":    [ { "path": "/opt/app/logs/app.log", "errorPattern": "^\\\\S+\\\\s+(ERROR|FATAL)\\\\b" } ],
"db": { "engine": "postgres", "url": "jdbc:postgresql://127.0.0.1:5432/appdb", "user": "monitor", "password": "…" },
"host": { "disks": ["/"], "processes": [ { "name": "app", "match": "java .*app\\\\.jar" } ], "ports": [ { "name": "HTTP", "port": 8080 } ] }`}</Code>
    <p className="small muted">Full example: samples/spring-postgres.json. Spring Boot logs to the console by default: set <span className="mono">logging.file.name</span> so the agent has a file to read.</p>
  </>
}

function Apache() {
  return <>
    <h4>Apache: add the response time</h4>
    <p>In the site or main config, then <span className="mono">sudo apachectl configtest &amp;&amp; sudo systemctl reload apache2</span>:</p>
    <Code>{`LogFormat "%h %l %u %t \\"%r\\" %>s %b \\"%{Referer}i\\" \\"%{User-Agent}i\\" %D %{UNIQUE_ID}e" fyntune
CustomLog \${APACHE_LOG_DIR}/access.log fyntune`}</Code>
    <p><span className="mono">%D</span> is in microseconds. <span className="mono">%{'{UNIQUE_ID}'}e</span> needs mod_unique_id; drop it if not available.</p>
    <h4>agent.json</h4>
    <Code>{`"accessLogs": [ { "path": "/var/log/apache2/access.log", "format": "apache" } ],
"appLogs":    [ { "path": "/var/log/apache2/error.log", "errorPattern": "(?i)\\\\[(php:)?(error|crit|alert|emerg)\\\\]|PHP (Fatal|Parse) error" } ]`}</Code>
    <p>For the database, use the MySQL or Postgres grants from the other tabs.</p>
  </>
}

function Other() {
  return <>
    <p>The agent does not care what the app is written in. It reads what the web server or reverse proxy in front of it writes.</p>
    <ul>
      <li><b>Behind nginx or Apache</b> (most Node, Python and PHP apps): use the nginx or Apache log format from the other tabs.</li>
      <li><b>IIS (.NET):</b> W3C logs differ in field order; use <span className="mono">"format": "custom"</span> with a regex (below).</li>
      <li><b>No log file at all:</b> the app can send each request to the agent on this machine. See PROTOCOL.md: a JSON POST to <span className="mono">http://127.0.0.1:7071/v1/events</span>.</li>
    </ul>
    <h4>A custom format</h4>
    <p>A regex with named groups <span className="mono">method</span>, <span className="mono">path</span>, <span className="mono">status</span> and <span className="mono">time</span>; optionally <span className="mono">rid</span> (request id) and <span className="mono">ts</span>. Set <span className="mono">timeUnit</span> to s, ms or us.</p>
    <Code>{`"accessLogs": [ { "path": "D:\\\\logs\\\\W3SVC1\\\\u_ex.log", "format": "custom", "timeUnit": "ms",
  "regex": "^\\\\S+ \\\\S+ \\\\S+ (?<method>[A-Z]+) (?<path>\\\\S+) \\\\S+ \\\\d+ \\\\S+ \\\\S+ \\\\S+ \\\\S+ (?<status>\\\\d{3}) \\\\d+ \\\\d+ (?<time>\\\\d+)$" } ]`}</Code>
    <h4>Sending events from the app (optional)</h4>
    <Code>{`curl -X POST http://127.0.0.1:7071/v1/events -H 'Content-Type: application/json' -d '[
  { "type": "REQUEST", "method": "POST", "path": "/api/orders/981/pay", "status": 500, "ms": 812, "requestId": "a1b2c3" },
  { "type": "ERROR", "source": "app", "requestId": "a1b2c3", "message": "Payment gateway timeout", "detail": "stack trace…" }
]'`}</Code>
  </>
}
