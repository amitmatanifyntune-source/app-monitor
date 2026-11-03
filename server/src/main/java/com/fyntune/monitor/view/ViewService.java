package com.fyntune.monitor.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyntune.monitor.common.Hist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * What the screens show for one app, built from what its agents sent: status, traffic and latency, the slowest and
 * failing endpoints, database health, errors and checks, and the servers the agents run on.
 */
@Service
public class ViewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int silentMinutes;

    public ViewService(JdbcTemplate jdbc, ObjectMapper json, @Value("${app.agent-silent-minutes}") int silentMinutes) {
        this.jdbc = jdbc;
        this.json = json;
        this.silentMinutes = silentMinutes;
    }

    static long nowMinute() { return System.currentTimeMillis() / 60000; }

    // ================================================================== traffic

    public record Point(long minute, long at, long requests, long errors, long refused, long slow, long p95, long avg) {}
    public record Window(int minutes, long requests, double perMinute, long errors, long refused, long slow, long p50, long p95, long max) {}
    public record Traffic(List<Point> series, Window last5, Window last15, Window last60, Window range) {}

    /** Per-minute traffic for the last {@code minutes} (agents added together), and totals for common windows. */
    public Traffic traffic(long appId, int minutes) {
        int range = Math.max(5, Math.min(minutes, 60 * 24 * 7));
        long now = nowMinute(), from = now - range;
        // one row per minute: agents added together
        Map<Long, long[]> hist = new TreeMap<>();
        Map<Long, long[]> sums = new HashMap<>(); // requests, errors, refused, slow, total, max
        jdbc.query("SELECT minute, requests, errors, refused, slow, total_ms, max_ms, hist FROM minute_stat WHERE app_id = ? AND minute >= ? AND minute < ?",
                rs -> {
                    long m = rs.getLong("minute");
                    Hist.add(hist.computeIfAbsent(m, k -> Hist.empty()), Hist.of(rs.getArray("hist")));
                    long[] s = sums.computeIfAbsent(m, k -> new long[6]);
                    s[0] += rs.getLong("requests"); s[1] += rs.getLong("errors"); s[2] += rs.getLong("refused"); s[3] += rs.getLong("slow");
                    s[4] += rs.getLong("total_ms"); s[5] = Math.max(s[5], rs.getLong("max_ms"));
                }, appId, from, now);
        // a step that keeps a week's chart readable: at most ~180 points
        int step = Math.max(1, range / 180);
        List<Point> series = new ArrayList<>();
        for (long m = from - Math.floorMod(from, step); m < now; m += step) {
            long[] h = Hist.empty(), s = new long[6];
            for (long k = m; k < m + step; k++) {
                if (hist.containsKey(k)) Hist.add(h, hist.get(k));
                long[] x = sums.get(k);
                if (x != null) { for (int i = 0; i < 5; i++) s[i] += x[i]; s[5] = Math.max(s[5], x[5]); }
            }
            series.add(new Point(m, m * 60000, s[0], s[1], s[2], s[3], Hist.pct(h, s[5], 0.95), s[0] == 0 ? 0 : s[4] / s[0]));
        }
        return new Traffic(series, window(hist, sums, now, 5), window(hist, sums, now, 15), window(hist, sums, now, 60), window(hist, sums, now, range));
    }

    private static Window window(Map<Long, long[]> hist, Map<Long, long[]> sums, long now, int minutes) {
        long[] h = Hist.empty(), s = new long[6];
        for (long m = now - minutes; m < now; m++) {
            if (hist.containsKey(m)) Hist.add(h, hist.get(m));
            long[] x = sums.get(m);
            if (x != null) { for (int i = 0; i < 5; i++) s[i] += x[i]; s[5] = Math.max(s[5], x[5]); }
        }
        return new Window(minutes, s[0], Math.round(s[0] * 10.0 / minutes) / 10.0, s[1], s[2], s[3], Hist.pct(h, s[5], 0.5), Hist.pct(h, s[5], 0.95), s[5]);
    }

    // ================================================================== endpoints

    public record EndpointStat(String method, String pattern, long requests, long errors, long refused, long slow, long avg, long p50, long p95,
                               long max, long totalMs, Instant lastAt) {}

    public List<EndpointStat> endpoints(long appId, int minutes) {
        long now = nowMinute(), from = now - Math.max(5, Math.min(minutes, 60 * 24 * 7));
        Map<String, long[]> sums = new LinkedHashMap<>(), hist = new HashMap<>();
        Map<String, Long> last = new HashMap<>();
        jdbc.query("""
                SELECT method, pattern, minute, requests, errors, refused, slow, total_ms, max_ms, hist
                  FROM endpoint_minute WHERE app_id = ? AND minute >= ? ORDER BY method, pattern""", rs -> {
            String k = rs.getString("method") + " " + rs.getString("pattern");
            long[] s = sums.computeIfAbsent(k, x -> new long[6]);
            s[0] += rs.getLong("requests"); s[1] += rs.getLong("errors"); s[2] += rs.getLong("refused"); s[3] += rs.getLong("slow");
            s[4] += rs.getLong("total_ms"); s[5] = Math.max(s[5], rs.getLong("max_ms"));
            Hist.add(hist.computeIfAbsent(k, x -> Hist.empty()), Hist.of(rs.getArray("hist")));
            last.merge(k, rs.getLong("minute"), Math::max);
        }, appId, from);
        List<EndpointStat> out = new ArrayList<>();
        sums.forEach((k, s) -> {
            int sp = k.indexOf(' ');
            long[] h = hist.get(k);
            out.add(new EndpointStat(k.substring(0, sp), k.substring(sp + 1), s[0], s[1], s[2], s[3], s[0] == 0 ? 0 : s[4] / s[0],
                    Hist.pct(h, s[5], 0.5), Hist.pct(h, s[5], 0.95), s[5], s[4], Instant.ofEpochSecond(last.get(k) * 60)));
        });
        // where the time goes first
        out.sort(Comparator.comparingLong(EndpointStat::totalMs).reversed());
        return out;
    }

    // ================================================================== issues

    public record IssueGroup(String type, String method, String path, long count, OffsetDateTime lastAt, String lastMessage, String lastRequestId, Integer lastStatus) {}
    public record Issues(int hours, Map<String, Long> totals, List<IssueGroup> groups, List<Map<String, Object>> recent) {}

    public Issues issues(long appId, int hours, String type) {
        int h = Math.max(1, Math.min(hours, 24 * 30));
        Map<String, Long> totals = new LinkedHashMap<>();
        jdbc.query("SELECT type, count(*) n FROM event WHERE app_id = ? AND at > now() - make_interval(hours => ?) GROUP BY type ORDER BY 2 DESC",
                rs -> { totals.put(rs.getString("type"), rs.getLong("n")); }, appId, h);
        // spaces on both sides: the text blocks around it start and end without one
        String typeFilter = type == null || type.isBlank() ? " " : " AND type = ? ";
        List<Object> args = new ArrayList<>(List.of(appId, h));
        if (!typeFilter.isBlank()) args.add(type.toUpperCase(Locale.ROOT));
        List<IssueGroup> groups = jdbc.query("""
                SELECT type, method, coalesce(path, source) AS path, count(*) AS n, max(at) AS last_at,
                       (array_agg(message ORDER BY at DESC))[1] AS last_message, (array_agg(request_id ORDER BY at DESC))[1] AS last_request,
                       (array_agg(status ORDER BY at DESC))[1] AS last_status
                  FROM event WHERE app_id = ? AND at > now() - make_interval(hours => ?)""" + typeFilter + """
                 GROUP BY type, method, coalesce(path, source)
                 ORDER BY CASE type WHEN 'ERROR' THEN 0 WHEN 'CHECK_FAIL' THEN 1 WHEN 'LOG_ERROR' THEN 2 WHEN 'SLOW' THEN 3 ELSE 4 END, n DESC
                 LIMIT 100""", (rs, i) -> new IssueGroup(rs.getString("type"), rs.getString("method"), rs.getString("path"), rs.getLong("n"),
                rs.getObject("last_at", OffsetDateTime.class), rs.getString("last_message"), rs.getString("last_request"), (Integer) rs.getObject("last_status")), args.toArray());
        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT id, at, type, source, request_id AS "requestId", method, path, status, ms, actor, message
                  FROM event WHERE app_id = ? AND at > now() - make_interval(hours => ?)""" + typeFilter + " ORDER BY at DESC LIMIT 200", args.toArray());
        return new Issues(h, totals, groups, recent);
    }

    public Map<String, Object> event(long appId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, at, type, source, agent_id AS "agentId", request_id AS "requestId", method, path, status, ms, actor, message, detail
                  FROM event WHERE app_id = ? AND id = ?""", appId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Every event with this request id (the X-Request-Id the app or its proxy returned). */
    public List<Map<String, Object>> trace(long appId, String requestId) {
        return jdbc.queryForList("""
                SELECT id, at, type, source, agent_id AS "agentId", request_id AS "requestId", method, path, status, ms, actor, message, detail
                  FROM event WHERE app_id = ? AND request_id = ? ORDER BY at""", appId, requestId);
    }

    // ================================================================== OpenTelemetry

    public record DepStat(String kind, String target, String operation, long calls, long errors, long avg, long p50, long p95, long max,
                          long totalMs, Instant lastAt) {}

    /** Outbound calls (databases, HTTP APIs, queues) from OpenTelemetry, where the time goes first. */
    public List<DepStat> dependencies(long appId, int minutes) {
        long from = nowMinute() - Math.max(5, Math.min(minutes, 60 * 24 * 7));
        Map<String, long[]> sums = new LinkedHashMap<>(), hist = new HashMap<>();
        Map<String, Long> last = new HashMap<>();
        jdbc.query("""
                SELECT kind, target, operation, minute, calls, errors, total_ms, max_ms, hist
                  FROM dependency_minute WHERE app_id = ? AND minute >= ?""", rs -> {
            String k = rs.getString("kind") + "\u0001" + rs.getString("target") + "\u0001" + rs.getString("operation");
            long[] x = sums.computeIfAbsent(k, y -> new long[4]);
            x[0] += rs.getLong("calls"); x[1] += rs.getLong("errors"); x[2] += rs.getLong("total_ms"); x[3] = Math.max(x[3], rs.getLong("max_ms"));
            Hist.add(hist.computeIfAbsent(k, y -> Hist.empty()), Hist.of(rs.getArray("hist")));
            last.merge(k, rs.getLong("minute"), Math::max);
        }, appId, from);
        List<DepStat> out = new ArrayList<>();
        sums.forEach((k, x) -> {
            String[] p = k.split("\u0001", -1);
            long[] h = hist.get(k);
            out.add(new DepStat(p[0], p[1], p[2], x[0], x[1], x[0] == 0 ? 0 : x[2] / x[0], Hist.pct(h, x[3], 0.5), Hist.pct(h, x[3], 0.95), x[3], x[2],
                    Instant.ofEpochSecond(last.get(k) * 60)));
        });
        out.sort(Comparator.comparingLong(DepStat::totalMs).reversed());
        return out;
    }

    public record TraceRow(long id, String traceId, OffsetDateTime at, String name, String service, long durationMs, boolean error, String reason,
                           Integer status, String requestId, int spanCount, String agentId) {}

    /** Kept traces, newest first. filter: all | error | slow; q: words of the name, a trace id or a request id. */
    public List<TraceRow> traces(long appId, int minutes, String filter, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, trace_id, at, name, service, duration_ms, error, reason, status, request_id, span_count, agent_id
                  FROM trace WHERE app_id = ? AND at > now() - make_interval(mins => ?)""");
        List<Object> args = new ArrayList<>(List.of(appId, Math.max(5, Math.min(minutes, 60 * 24 * 7))));
        if ("error".equals(filter)) sql.append(" AND error");
        else if ("slow".equals(filter)) sql.append(" AND reason = 'slow'");
        if (q != null && !q.isBlank()) {
            sql.append(" AND (name ILIKE ? OR trace_id = ? OR request_id = ?)");
            String t = q.trim();
            args.add("%" + t.replace("%", "\\%").replace("_", "\\_") + "%");
            args.add(t.toLowerCase(Locale.ROOT));
            args.add(t);
        }
        sql.append(" ORDER BY at DESC LIMIT 200");
        return jdbc.query(sql.toString(), (rs, i) -> new TraceRow(rs.getLong("id"), rs.getString("trace_id"), rs.getObject("at", OffsetDateTime.class),
                rs.getString("name"), rs.getString("service"), rs.getLong("duration_ms"), rs.getBoolean("error"), rs.getString("reason"),
                (Integer) rs.getObject("status"), rs.getString("request_id"), rs.getInt("span_count"), rs.getString("agent_id")), args.toArray());
    }

    /** One kept trace with its spans. */
    public Map<String, Object> traceDetail(long appId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, trace_id AS "traceId", at, name, service, duration_ms AS "durationMs", error, reason, status, request_id AS "requestId",
                       span_count AS "spanCount", agent_id AS "agentId", spans::text AS spans
                  FROM trace WHERE app_id = ? AND id = ?""", appId, id);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("spans", read((String) m.get("spans")));
        return m;
    }

    // ================================================================== checks

    public record CheckState(String name, String kind, boolean health, boolean ok, OffsetDateTime at, Long ms, Double value, String message,
                             Object refs, long okCount24h, long failCount24h, OffsetDateTime lastFailAt, List<Map<String, Object>> recent) {}

    public List<CheckState> checks(long appId) {
        Map<String, Boolean> health = new HashMap<>();
        jdbc.query("SELECT name, is_health FROM check_def WHERE app_id = ?", rs -> { health.put(rs.getString("name"), rs.getBoolean("is_health")); }, appId);
        List<CheckState> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT DISTINCT ON (name) name, kind, ok, at, ms, value, message, refs::text AS refs FROM check_result
                 WHERE app_id = ? AND at > now() - interval '7 days' ORDER BY name, at DESC""", appId)) {
            String name = (String) r.get("name");
            Map<String, Object> c = jdbc.queryForMap("""
                    SELECT count(*) FILTER (WHERE ok) AS ok, count(*) FILTER (WHERE NOT ok) AS fail, max(at) FILTER (WHERE NOT ok) AS last_fail
                      FROM check_result WHERE app_id = ? AND name = ? AND at > now() - interval '24 hours'""", appId, name);
            List<Map<String, Object>> recent = jdbc.queryForList(
                    "SELECT at, ok, ms, value FROM check_result WHERE app_id = ? AND name = ? ORDER BY at DESC LIMIT 60", appId, name);
            out.add(new CheckState(name, (String) r.get("kind"), health.getOrDefault(name, false), (Boolean) r.get("ok"), odt(r.get("at")),
                    r.get("ms") == null ? null : ((Number) r.get("ms")).longValue(), r.get("value") == null ? null : ((Number) r.get("value")).doubleValue(),
                    (String) r.get("message"), read((String) r.get("refs")), ((Number) c.get("ok")).longValue(), ((Number) c.get("fail")).longValue(),
                    odt(c.get("last_fail")), recent));
        }
        out.sort(Comparator.comparing((CheckState s) -> s.ok()).thenComparing(s -> !s.health()).thenComparing(CheckState::name));
        return out;
    }

    // ================================================================== database and host

    /** The latest database snapshot from each agent that sends one. */
    public List<Map<String, Object>> db(long appId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT DISTINCT ON (agent_id) agent_id AS "agentId", at, engine, version, snapshot::text AS snapshot
                  FROM db_sample WHERE app_id = ? AND at > now() - interval '1 day' ORDER BY agent_id, at DESC""", appId)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("snapshot", read((String) r.get("snapshot")));
            out.add(m);
        }
        return out;
    }

    public record HostView(String agentId, String agentName, String host, Object latest, List<Map<String, Object>> series, Object spec, Instant specAt) {}

    public List<HostView> host(long appId, int minutes) {
        int m = Math.max(5, Math.min(minutes, 60 * 24));
        List<HostView> out = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT id, name, host, spec::text AS spec, spec_at FROM agent WHERE app_id = ? AND NOT revoked ORDER BY created_at", appId)) {
            String id = (String) a.get("id");
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT at, (sample->>'cpu')::float AS cpu, (sample->>'memUsedMb')::bigint AS "memUsedMb", (sample->>'memTotalMb')::bigint AS "memTotalMb",
                           (sample->>'load1')::float AS load1, (sample->'agent'->>'cpu')::float AS "agentCpu",
                           (sample->'agent'->>'rssMb')::bigint AS "agentRssMb"
                      FROM host_sample WHERE app_id = ? AND agent_id = ? AND at > now() - make_interval(mins => ?) ORDER BY at""", appId, id, m);
            List<String> latest = jdbc.queryForList("SELECT sample::text FROM host_sample WHERE app_id = ? AND agent_id = ? ORDER BY at DESC LIMIT 1", String.class, appId, id);
            out.add(new HostView(id, (String) a.get("name"), (String) a.get("host"), latest.isEmpty() ? null : read(latest.get(0)), rows,
                    a.get("spec") == null ? null : read((String) a.get("spec")), a.get("spec_at") instanceof java.sql.Timestamp t ? t.toInstant()
                    : a.get("spec_at") instanceof OffsetDateTime o ? o.toInstant() : null));
        }
        return out;
    }

    public List<Map<String, Object>> agents(long appId) {
        return jdbc.queryForList("""
                SELECT id, name, host, version, last_seen AS "lastSeen", revoked, created_at AS "createdAt",
                       last_seen IS NOT NULL AND last_seen > now() - make_interval(mins => ?) AS online
                  FROM agent WHERE app_id = ? ORDER BY created_at""", silentMinutes, appId);
    }

    // ================================================================== status and summary

    public record Highlight(String level, String title, String text, String tab, String ref) {}
    public record Status(String status, List<String> reasons) {}
    public record Summary(String code, String name, String environment, String status, List<String> reasons, Instant at, List<Highlight> highlights, int slowMs) {}

    /**
     * NO_DATA: no agent has reported yet. DOWN: a health check is failing, or every agent has gone silent.
     * DEGRADED: server errors in the last 15 minutes, another check failing, or 95% of requests slower than the app's
     * slow threshold. UP otherwise.
     */
    public Status status(long appId) {
        List<String> down = new ArrayList<>(), warn = new ArrayList<>();
        Map<String, Object> ag = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE last_seen IS NOT NULL) AS seen,
                       count(*) FILTER (WHERE last_seen > now() - make_interval(mins => ?)) AS online
                  FROM agent WHERE app_id = ? AND NOT revoked""", silentMinutes, appId);
        long seen = ((Number) ag.get("seen")).longValue(), online = ((Number) ag.get("online")).longValue();
        if (seen == 0) return new Status("NO_DATA", List.of("No agent has reported yet"));
        if (online == 0) down.add("No agent has reported for over " + silentMinutes + " minutes");
        for (CheckState c : checks(appId)) {
            if (c.ok() || c.at() == null || c.at().isBefore(OffsetDateTime.now().minusMinutes(30))) continue;
            (c.health() ? down : warn).add(c.name() + ": " + (c.message() == null ? "failing" : c.message()));
        }
        Long errors = jdbc.queryForObject("SELECT count(*) FROM event WHERE app_id = ? AND type = 'ERROR' AND at > now() - interval '15 minutes'", Long.class, appId);
        if (errors != null && errors > 0) warn.add(errors + " server error" + (errors == 1 ? "" : "s") + " in the last 15 minutes");
        Integer slowMs = jdbc.queryForObject("SELECT slow_ms FROM app WHERE id = ?", Integer.class, appId);
        Window w = traffic(appId, 5).last5();
        if (w.requests() > 0 && slowMs != null && w.p95() > slowMs) warn.add("95% of requests within " + ms(w.p95()) + " (slow above " + ms(slowMs) + ")");
        if (!down.isEmpty()) { down.addAll(warn); return new Status("DOWN", down); }
        if (!warn.isEmpty()) return new Status("DEGRADED", warn);
        return new Status("UP", List.of());
    }

    /** The few things someone watching this app needs to know now, most serious first. */
    public Summary summary(long appId) {
        Map<String, Object> app = jdbc.queryForMap("SELECT code, name, environment, slow_ms FROM app WHERE id = ?", appId);
        Status st = status(appId);
        Traffic t = traffic(appId, 60);
        Issues iss = issues(appId, 24, null);
        List<Highlight> out = new ArrayList<>();

        out.add(switch (st.status()) {
            case "UP" -> new Highlight("good", "Status", "Up · agents reporting · checks passing", "overview", null);
            case "NO_DATA" -> new Highlight("info", "Status", "Waiting for the first report from an agent. See Setup.", "setup", null);
            default -> new Highlight(st.status().equals("DOWN") ? "bad" : "warn", "Status", String.join(" · ", st.reasons()), "checks", null);
        });

        long errors = iss.totals().getOrDefault("ERROR", 0L), refused = iss.totals().getOrDefault("REFUSED", 0L),
             slow = iss.totals().getOrDefault("SLOW", 0L), logErrors = iss.totals().getOrDefault("LOG_ERROR", 0L);
        IssueGroup topErr = iss.groups().stream().filter(g -> "ERROR".equals(g.type())).findFirst().orElse(null);
        out.add(errors > 0
                ? new Highlight("bad", "Errors (24 h)", errors + " server error" + (errors == 1 ? "" : "s") + ", most on " + label(topErr)
                    + (topErr.lastMessage() == null ? "" : ": " + cut(topErr.lastMessage(), 120)), "issues", topErr.lastRequestId())
                : new Highlight("good", "Errors (24 h)", "No server errors", "issues", null));
        if (logErrors > 0) {
            IssueGroup g = iss.groups().stream().filter(x -> "LOG_ERROR".equals(x.type())).findFirst().orElse(null);
            out.add(new Highlight("warn", "Log errors (24 h)", logErrors + " error line" + (logErrors == 1 ? "" : "s") + " in app logs"
                    + (g == null || g.lastMessage() == null ? "" : " · latest: " + cut(g.lastMessage(), 110)), "issues", null));
        }
        if (refused > 0) {
            IssueGroup g = iss.groups().stream().filter(x -> "REFUSED".equals(x.type())).findFirst().orElse(null);
            out.add(new Highlight(refused >= 50 ? "warn" : "info", "Refused (24 h)", refused + " request" + (refused == 1 ? "" : "s")
                    + " refused (4xx); most on " + label(g) + " (" + g.count() + "×)", "issues", g.lastRequestId()));
        }
        List<CheckState> failing = checks(appId).stream().filter(c -> !c.ok()).toList();
        if (!failing.isEmpty())
            out.add(new Highlight(failing.stream().anyMatch(CheckState::health) ? "bad" : "warn", "Checks failing",
                    String.join(" · ", failing.stream().map(c -> c.name() + (c.value() == null ? "" : " (" + fmt(c.value()) + ")")).toList()), "checks", null));

        Window w = t.last5();
        int slowMs = ((Number) app.get("slow_ms")).intValue();
        out.add(new Highlight(w.requests() > 0 && w.p95() >= slowMs ? "warn" : "info", "Load (5 min)",
                w.perMinute() + " requests/min · 95% within " + ms(w.p95()) + (slow > 0 ? " · " + slow + " slow request" + (slow == 1 ? "" : "s") + " in 24 h" : ""),
                "apis", null));

        for (Map<String, Object> d : db(appId)) {
            Object snap = d.get("snapshot");
            if (snap instanceof Map<?, ?> s && s.get("summary") instanceof String text)
                out.add(new Highlight(Boolean.TRUE.equals(s.get("warn")) ? "warn" : "info", "Database", d.get("engine") + " " + d.get("version") + " · " + text, "database", null));
        }
        return new Summary((String) app.get("code"), (String) app.get("name"), (String) app.get("environment"), st.status(), st.reasons(), Instant.now(), out,
                ((Number) app.get("slow_ms")).intValue());
    }

    /** One line per app for the all-apps screen. */
    public Map<String, Object> card(long appId) {
        Map<String, Object> app = jdbc.queryForMap("SELECT code, name, environment, description FROM app WHERE id = ?", appId);
        Status st = status(appId);
        Window w = traffic(appId, 15).last15();
        Long errors = jdbc.queryForObject("SELECT count(*) FROM event WHERE app_id = ? AND type = 'ERROR' AND at > now() - interval '24 hours'", Long.class, appId);
        Map<String, Object> ag = jdbc.queryForMap("SELECT count(*) AS n, max(last_seen) AS last FROM agent WHERE app_id = ? AND NOT revoked", appId);
        Map<String, Object> m = new LinkedHashMap<>(app);
        m.put("status", st.status());
        m.put("reasons", st.reasons());
        m.put("perMinute", w.perMinute());
        m.put("p95", w.p95());
        m.put("errors24h", errors);
        m.put("agents", ag.get("n"));
        m.put("lastSeen", ag.get("last"));
        return m;
    }

    // ================================================================== helpers

    private static String label(IssueGroup g) {
        return g == null ? "?" : (g.method() == null ? "" : g.method() + " ") + (g.path() == null ? "?" : g.path());
    }

    static String ms(long v) { return v >= 1000 ? String.format(Locale.ROOT, "%.1f s", v / 1000.0) : v + " ms"; }
    static String cut(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v); }

    private static OffsetDateTime odt(Object o) {
        if (o instanceof OffsetDateTime d) return d;
        if (o instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    private Object read(String s) {
        try { return s == null ? null : json.readValue(s, Object.class); } catch (Exception e) { return null; }
    }
}
