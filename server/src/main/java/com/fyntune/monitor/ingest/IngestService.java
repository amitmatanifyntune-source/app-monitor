package com.fyntune.monitor.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyntune.monitor.common.Hist;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Stores agent batches. A batch whose seq was already stored is ignored, so resends never count twice. */
@Service
public class IngestService {
    /** Per batch; an agent that sends more is cut off at these. */
    static final int MAX_EVENTS = 1000, MAX_ENDPOINTS = 5000, MAX_CHECKS = 200, MAX_DEPS = 5000, MAX_TRACES = 120, MAX_TRACE_JSON = 400_000;
    private static final Set<String> DEP_KINDS = Set.of("db", "http", "queue", "other");
    private static final Set<String> TYPES = Set.of("ERROR", "REFUSED", "SLOW", "LOG_ERROR", "CHECK_FAIL", "APP");

    // adds two histograms element by element
    private static final String ADD_HIST = "ARRAY(SELECT coalesce(a, 0) + coalesce(b, 0) FROM unnest(%s.hist, EXCLUDED.hist) AS t(a, b))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IngestService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Result(boolean accepted, long seq) {}

    @Transactional
    public Result store(AgentAuth.Agent agent, Batch b) {
        // the row lock orders batches from the same agent
        Long last = jdbc.queryForObject("SELECT last_seq FROM agent WHERE id = ? FOR UPDATE", Long.class, agent.id());
        if (last != null && b.seq() <= last) {
            jdbc.update("UPDATE agent SET last_seen = now() WHERE id = ?", agent.id());
            return new Result(false, last);
        }
        long app = agent.appId();

        if (b.minutes() != null) for (Batch.Minute m : b.minutes())
            jdbc.update("""
                    INSERT INTO minute_stat (app_id, agent_id, minute, requests, errors, refused, slow, total_ms, max_ms, hist)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (app_id, minute, agent_id) DO UPDATE SET
                      requests = minute_stat.requests + EXCLUDED.requests, errors = minute_stat.errors + EXCLUDED.errors,
                      refused = minute_stat.refused + EXCLUDED.refused, slow = minute_stat.slow + EXCLUDED.slow,
                      total_ms = minute_stat.total_ms + EXCLUDED.total_ms, max_ms = greatest(minute_stat.max_ms, EXCLUDED.max_ms),
                      hist = """ + ADD_HIST.formatted("minute_stat"),
                    app, agent.id(), m.minute(), m.requests(), m.errors(), m.refused(), m.slow(), m.totalMs(), m.maxMs(), array(m.hist()));

        if (b.endpoints() != null) for (Batch.Endpoint e : b.endpoints().stream().limit(MAX_ENDPOINTS).toList()) {
            if (e.pattern() == null || e.method() == null) continue;
            jdbc.update("""
                    INSERT INTO endpoint_minute (app_id, minute, method, pattern, requests, errors, refused, slow, total_ms, max_ms, hist)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (app_id, minute, method, pattern) DO UPDATE SET
                      requests = endpoint_minute.requests + EXCLUDED.requests, errors = endpoint_minute.errors + EXCLUDED.errors,
                      refused = endpoint_minute.refused + EXCLUDED.refused, slow = endpoint_minute.slow + EXCLUDED.slow,
                      total_ms = endpoint_minute.total_ms + EXCLUDED.total_ms, max_ms = greatest(endpoint_minute.max_ms, EXCLUDED.max_ms),
                      hist = """ + ADD_HIST.formatted("endpoint_minute"),
                    app, e.minute(), cut(e.method(), 8), cut(e.pattern(), 300), e.requests(), e.errors(), e.refused(), e.slow(), e.totalMs(), e.maxMs(), array(e.hist()));
        }

        if (b.events() != null) for (Batch.Event e : b.events().stream().limit(MAX_EVENTS).toList()) {
            String type = e.type() == null ? "APP" : e.type().toUpperCase(Locale.ROOT);
            event(app, agent.id(), at(e.at()), TYPES.contains(type) ? type : "APP", e.source(), e.requestId(), e.method(), e.path(), e.status(), e.ms(), e.actor(), e.message(), e.detail());
        }

        if (b.checks() != null) for (Batch.Check c : b.checks().stream().limit(MAX_CHECKS).toList()) {
            if (c.name() == null) continue;
            jdbc.update("INSERT INTO check_result (app_id, agent_id, name, kind, at, ok, ms, value, message, refs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    app, agent.id(), cut(c.name(), 80), cut(c.kind() == null ? "HTTP" : c.kind(), 8), at(c.at()), c.ok(), c.ms(), c.value(),
                    cut(c.message(), 600), write(c.refs() == null ? List.of() : c.refs().stream().limit(10).toList()));
            if (!c.ok())
                event(app, agent.id(), at(c.at()), "CHECK_FAIL", "check:" + c.name(), null, null, null, null, c.ms(), null, c.name() + ": " + c.message(), null);
        }

        if (b.deps() != null) for (Batch.Dep d : b.deps().stream().limit(MAX_DEPS).toList()) {
            if (d.target() == null || d.kind() == null || d.hist() == null) continue;
            String kind = d.kind().toLowerCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO dependency_minute (app_id, minute, kind, target, operation, calls, errors, total_ms, max_ms, hist)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (app_id, minute, kind, target, operation) DO UPDATE SET
                      calls = dependency_minute.calls + EXCLUDED.calls, errors = dependency_minute.errors + EXCLUDED.errors,
                      total_ms = dependency_minute.total_ms + EXCLUDED.total_ms, max_ms = greatest(dependency_minute.max_ms, EXCLUDED.max_ms),
                      hist = """ + ADD_HIST.formatted("dependency_minute"),
                    app, d.minute(), DEP_KINDS.contains(kind) ? kind : "other", cut(d.target(), 200), cut(d.operation() == null ? "" : d.operation(), 200),
                    d.calls(), d.errors(), d.totalMs(), d.maxMs(), array(d.hist()));
        }

        if (b.traces() != null) for (JsonNode t : b.traces().stream().limit(MAX_TRACES).toList()) {
            String traceId = t.path("traceId").asText("");
            JsonNode spans = t.path("spans");
            if (!traceId.matches("[0-9a-f]{16,32}") || !spans.isArray()) continue;
            String spansJson = spans.toString();
            if (spansJson.length() > MAX_TRACE_JSON) continue;
            String reason = t.path("reason").asText("sample");
            jdbc.update("""
                    INSERT INTO trace (app_id, agent_id, trace_id, at, name, service, duration_ms, error, reason, status, request_id, span_count, spans)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                    app, agent.id(), traceId, at(t.path("at").isNumber() ? t.path("at").asLong() : null), cut(t.path("name").asText("(unnamed)"), 300),
                    cut(t.path("service").asText(null), 120), Math.max(0, t.path("durationMs").asLong(0)), t.path("error").asBoolean(false),
                    Set.of("error", "slow", "sample").contains(reason) ? reason : "sample", t.path("status").isInt() ? t.path("status").asInt() : null,
                    cut(t.path("requestId").asText(null), 64), Math.max(spans.size(), t.path("spanCount").asInt(0)), spansJson);
        }

        if (b.db() != null && b.db().snapshot() != null)
            jdbc.update("INSERT INTO db_sample (app_id, agent_id, at, engine, version, snapshot) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                    app, agent.id(), at(b.db().at()), cut(b.db().engine(), 12), cut(b.db().version(), 40), b.db().snapshot().toString());

        if (b.hostStats() != null && b.hostStats().isObject())
            jdbc.update("INSERT INTO host_sample (app_id, agent_id, at, sample) VALUES (?, ?, ?, ?::jsonb)",
                    app, agent.id(), at(b.hostStats().path("at").isNumber() ? b.hostStats().path("at").asLong() : null), b.hostStats().toString());

        // the server's specification comes about hourly; kept as the agent's latest, capped so one agent cannot store a lot
        if (b.spec() != null && b.spec().isObject() && b.spec().toString().length() <= 20_000)
            jdbc.update("UPDATE agent SET spec = ?::jsonb, spec_at = now() WHERE id = ?", b.spec().toString(), agent.id());

        jdbc.update("UPDATE agent SET last_seq = ?, last_seen = now(), host = coalesce(?, host), version = coalesce(?, version) WHERE id = ?",
                b.seq(), cut(b.host(), 120), cut(b.agentVersion(), 20), agent.id());
        return new Result(true, b.seq());
    }

    private void event(long app, String agent, Timestamp at, String type, String source, String requestId, String method, String path,
                       Integer status, Long ms, String actor, String message, String detail) {
        jdbc.update("""
                INSERT INTO event (app_id, agent_id, at, type, source, request_id, method, path, status, ms, actor, message, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                app, agent, at, type, cut(source, 80), cut(requestId, 64), cut(method, 8), cut(path, 300), status, ms, cut(actor, 120),
                cut(message, 600), cut(detail, 8000));
    }

    /** The settings an agent pulls: slow threshold, masking and path rules, and the app's enabled checks. */
    public Map<String, Object> config(long appId) {
        Map<String, Object> app = jdbc.queryForMap("SELECT slow_ms, mask::text AS mask, route_rules::text AS rules FROM app WHERE id = ?", appId);
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT name, kind, config::text AS config, interval_sec FROM check_def WHERE app_id = ? AND enabled ORDER BY id", appId)) {
            Map<String, Object> m = new LinkedHashMap<>(read((String) c.get("config")));
            m.put("name", c.get("name"));
            m.put("kind", c.get("kind"));
            m.put("intervalSec", c.get("interval_sec"));
            checks.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slowMs", app.get("slow_ms"));
        out.put("mask", readList((String) app.get("mask")));
        out.put("routeRules", readList((String) app.get("rules")));
        out.put("checks", checks);
        // agents 0.2+ compress batches only when the server says it can unpack them (GzipRequestFilter)
        out.put("gzip", true);
        return out;
    }

    private static Timestamp at(Long ms) {
        long now = System.currentTimeMillis();
        // a clock far off on the agent's side would put data in the wrong place; keep it near now
        long v = ms == null || Math.abs(ms - now) > 7L * 24 * 3600 * 1000 ? now : ms;
        return Timestamp.from(Instant.ofEpochMilli(v));
    }

    private java.sql.Array array(long[] hist) {
        return jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint", Hist.boxed(hist)));
    }

    static String cut(String s, int n) {
        return s == null ? null : s.length() > n ? s.substring(0, n) : s;
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String s) {
        try { return s == null ? Map.of() : json.readValue(s, Map.class); } catch (Exception e) { return Map.of(); }
    }

    private List<?> readList(String s) {
        try { return s == null ? List.of() : json.readValue(s, List.class); } catch (Exception e) { return List.of(); }
    }
}
