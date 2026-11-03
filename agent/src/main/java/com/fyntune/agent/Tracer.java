package com.fyntune.agent;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OpenTelemetry spans into what the monitor shows:
 * <ul>
 *   <li><b>dependencies</b>: every outbound call (database, HTTP API, queue) counted per minute by kind, target and
 *       operation, with a response-time histogram, like the APIs tab does for incoming requests;</li>
 *   <li><b>traces</b>: whole requests as span trees, kept only when they failed, were slow, or fell in the sample, and
 *       never more than a set number a minute.</li>
 * </ul>
 * Every text that leaves is masked; SQL loses its literal values first. Memory is bounded: past the limits spans are
 * counted as dropped, and the app is never slowed down by it (the receiver always answers at once).
 */
public final class Tracer {
    static final int MAX_DEP_KEYS_PER_MINUTE = 300, MAX_SPANS_PER_TRACE = 300, MAX_PENDING_TRACES = 2000;
    /** Spans held at once across all unfinished traces: the memory ceiling of the trace buffer (roughly 10–20 MB). */
    static final int MAX_PENDING_SPANS = 20_000;
    /** Trace ids already decided against, so their late spans are not buffered again. */
    static final int MAX_DECIDED = 10_000;
    /** A trace is complete this long after its last span once its root has arrived, or this long after its first span. */
    static final long QUIET_MS = 3_000, MAX_WAIT_MS = 30_000;

    public record Settings(double sampleRatio, int maxTracesPerMinute, int maxSpansPerMinute) {
        static Settings of(AgentConfig.Otlp o) {
            return new Settings(o == null || o.sampleRatio() == null ? 0.05 : Math.max(0, Math.min(1, o.sampleRatio())),
                    o == null || o.maxTracesPerMinute() == null ? 30 : Math.max(0, o.maxTracesPerMinute()),
                    o == null || o.maxSpansPerMinute() == null ? 50_000 : Math.max(100, o.maxSpansPerMinute()));
        }
    }

    private static final class Dep { final Hist h = new Hist(); long errors; }

    private static final class Pending {
        final List<OtlpDecoder.Span> spans = new ArrayList<>();
        final long firstAt;
        long lastAt;
        boolean root, error;
        Pending(long now) { firstAt = now; lastAt = now; }
    }

    private final Masker masker;
    private final RouteNormalizer routes;
    private final Aggregator agg;
    private volatile Settings settings;

    private final Map<Long, Map<String, Dep>> deps = new TreeMap<>();
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
    private int pendingSpans;
    private final LinkedHashMap<String, Boolean> decided = new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MAX_DECIDED; }
    };
    private final List<Map<String, Object>> kept = new ArrayList<>();

    private long spanMinute, spansThisMinute, keptMinute;
    private int errorSlowKept, sampledKept;
    // since the last batch, for the agent-overhead figures
    private long received, dropped, tracesKept, tracesDropped;

    public Tracer(Masker masker, RouteNormalizer routes, Aggregator agg, Settings settings) {
        this.masker = masker;
        this.routes = routes;
        this.agg = agg;
        this.settings = settings;
    }

    public void setSettings(Settings s) { this.settings = s; }
    public Settings settings() { return settings; }

    // ------------------------------------------------------------------ intake

    /**
     * Spans from one export. Every client span is counted in the dependencies; spans are buffered for a trace only until
     * its root arrives, and then only if the trace will be kept (failed, slow, or sampled with room left this minute).
     * Most traces are therefore let go at once instead of waiting in memory. Returns how many spans were taken.
     */
    public synchronized int accept(List<OtlpDecoder.Span> spans, long now) {
        long minute = now / 60_000;
        if (minute != spanMinute) { spanMinute = minute; spansThisMinute = 0; }
        rollKeptMinute(now);
        int taken = 0;
        for (OtlpDecoder.Span s : spans) {
            received++;
            if (s.traceId() == null || s.traceId().isEmpty() || s.spanId() == null || spansThisMinute >= settings.maxSpansPerMinute()) { dropped++; continue; }
            spansThisMinute++;
            taken++;
            dependency(s);
            if (decided.containsKey(s.traceId())) continue;   // a late span of a trace already let go
            Pending p = pending.get(s.traceId());
            if (p == null) {
                if (pending.size() >= MAX_PENDING_TRACES) evictOldest();
                p = new Pending(now);
                pending.put(s.traceId(), p);
            }
            if (p.spans.size() < MAX_SPANS_PER_TRACE) { p.spans.add(s); pendingSpans++; } else dropped++;
            p.lastAt = now;
            if (failed(s)) p.error = true;
            boolean isRoot = s.parentId() == null || s.kind() == OtlpDecoder.SERVER || s.kind() == OtlpDecoder.CONSUMER;
            if (isRoot) {
                p.root = true;
                boolean slow = s.durationUs() / 1000 >= agg.slowMs();
                if (!worthKeeping(s.traceId(), p.error, slow)) {
                    // a failed, slow or sampled trace over this minute's limit is counted as dropped; an ordinary one is just not kept
                    if (p.error || slow || sampled(s.traceId(), settings.sampleRatio())) tracesDropped++;
                    letGo(s.traceId(), p);
                }
            }
            while (pendingSpans > MAX_PENDING_SPANS && !pending.isEmpty()) evictOldest();
        }
        return taken;
    }

    /** Whether a trace could still be kept, given the limits already used this minute. */
    private boolean worthKeeping(String traceId, boolean error, boolean slow) {
        int max = settings.maxTracesPerMinute();
        if (error || slow) return errorSlowKept < max;
        return sampled(traceId, settings.sampleRatio()) && sampledKept < Math.max(1, max / 3);
    }

    private void letGo(String traceId, Pending p) {
        pending.remove(traceId);
        pendingSpans -= p.spans.size();
        decided.put(traceId, Boolean.TRUE);
    }

    private void evictOldest() {
        var it = pending.entrySet().iterator();
        var e = it.next();
        pendingSpans -= e.getValue().spans.size();
        dropped += e.getValue().spans.size();
        tracesDropped++;
        decided.put(e.getKey(), Boolean.TRUE);
        it.remove();
    }

    private void rollKeptMinute(long now) {
        long minute = now / 60_000;
        if (minute != keptMinute) { keptMinute = minute; errorSlowKept = 0; sampledKept = 0; }
    }

    /** Finishes traces that are complete; called every couple of seconds. */
    public synchronized void tick(long now) {
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Pending p = e.getValue();
            if ((p.root && now - p.lastAt >= QUIET_MS) || now - p.firstAt >= MAX_WAIT_MS) {
                it.remove();
                pendingSpans -= p.spans.size();
                decided.put(e.getKey(), Boolean.TRUE);
                finish(e.getKey(), p.spans, now);
            }
        }
    }

    // ------------------------------------------------------------------ dependencies

    record DepKey(String kind, String target, String operation) {}

    /** Kind, target and operation of an outbound call, or null for spans that are not one. */
    DepKey classify(OtlpDecoder.Span s) {
        Map<String, Object> a = s.attrs();
        String dbSystem = str(a.get("db.system"), a.get("db.system.name"));
        if (dbSystem != null) {
            String db = str(a.get("db.namespace"), a.get("db.name"));
            String host = str(a.get("server.address"), a.get("net.peer.name"));
            String target = dbSystem + (db != null ? " " + db : "") + (host != null ? " @ " + host : "");
            return new DepKey("db", cut(target, 200), cut(sqlSummary(s), 200));
        }
        String msg = str(a.get("messaging.system"));
        if (msg != null) {
            String dest = str(a.get("messaging.destination.name"), a.get("messaging.destination"));
            return new DepKey("queue", cut(msg + (dest != null ? " " + dest : ""), 200), cut(str(a.get("messaging.operation.type"), a.get("messaging.operation"), s.name()), 200));
        }
        String method = str(a.get("http.request.method"), a.get("http.method"));
        if (s.kind() == OtlpDecoder.CLIENT && method != null) {
            String full = str(a.get("url.full"), a.get("http.url"));
            String host = str(a.get("server.address"), a.get("net.peer.name"));
            Object port = a.get("server.port") != null ? a.get("server.port") : a.get("net.peer.port");
            String path = str(a.get("url.path"), a.get("http.target"));
            if (full != null) {
                try {
                    URI u = URI.create(full);
                    if (host == null) host = u.getHost();
                    if (port == null && u.getPort() > 0) port = u.getPort();
                    if (path == null) path = u.getRawPath();
                } catch (Exception ignored) { /* not a URL */ }
            }
            if (path != null && path.indexOf('?') >= 0) path = path.substring(0, path.indexOf('?'));
            String p = port == null ? "" : String.valueOf(port);
            String target = (host == null ? "unknown host" : host) + (p.isEmpty() || p.equals("80") || p.equals("443") ? "" : ":" + p);
            return new DepKey("http", cut(target, 200), cut(method.toUpperCase(Locale.ROOT) + " " + (path == null ? "/" : routes.normalize(path)), 200));
        }
        if (s.kind() == OtlpDecoder.CLIENT || s.kind() == OtlpDecoder.PRODUCER)
            return new DepKey("other", cut(masker.mask(s.name(), 200), 200), "");
        return null;
    }

    private void dependency(OtlpDecoder.Span s) {
        DepKey k = classify(s);
        if (k == null) return;
        long minute = (s.startNs() > 0 ? s.startNs() / 1_000_000 : System.currentTimeMillis()) / 60_000;
        Map<String, Dep> m = deps.computeIfAbsent(minute, x -> new HashMap<>());
        String key = k.kind() + "\u0001" + k.target() + "\u0001" + k.operation();
        if (!m.containsKey(key) && m.size() >= MAX_DEP_KEYS_PER_MINUTE) key = k.kind() + "\u0001(more targets)\u0001";
        Dep d = m.computeIfAbsent(key, x -> new Dep());
        d.h.add(s.durationUs() / 1000);
        if (failed(s)) d.errors++;
    }

    /** Finished minutes of dependency counts. */
    public synchronized List<Map<String, Object>> drainDependencies(long beforeMinute) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var it = deps.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey() >= beforeMinute) break;
            e.getValue().forEach((key, d) -> {
                String[] p = key.split("\u0001", -1);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("minute", e.getKey());
                row.put("kind", p[0]);
                row.put("target", p[1]);
                row.put("operation", p[2]);
                row.put("calls", d.h.count);
                row.put("errors", d.errors);
                row.put("totalMs", d.h.total);
                row.put("maxMs", d.h.max);
                row.put("hist", d.h.counts());
                out.add(row);
            });
            it.remove();
        }
        return out;
    }

    // ------------------------------------------------------------------ traces

    private void finish(String traceId, List<OtlpDecoder.Span> spans, long now) {
        if (spans.isEmpty()) return;
        spans.sort(Comparator.comparingLong(OtlpDecoder.Span::startNs));
        Set<String> ids = new HashSet<>();
        for (OtlpDecoder.Span s : spans) ids.add(s.spanId());
        OtlpDecoder.Span root = spans.stream().filter(s -> s.parentId() == null).findFirst()
                .orElse(spans.stream().filter(s -> !ids.contains(s.parentId())).findFirst().orElse(spans.get(0)));
        long start = spans.get(0).startNs();
        long end = spans.stream().mapToLong(OtlpDecoder.Span::endNs).max().orElse(start);
        long durMs = Math.max(root.durationUs(), (end - start) / 1000) / 1000;
        boolean error = spans.stream().anyMatch(Tracer::failed);
        boolean slow = durMs >= agg.slowMs();
        boolean sampled = sampled(traceId, settings.sampleRatio());

        rollKeptMinute(now);
        int max = settings.maxTracesPerMinute();
        String reason;
        if ((error || slow) && errorSlowKept < max) { errorSlowKept++; reason = error ? "error" : "slow"; }
        else if (sampled && sampledKept < Math.max(1, max / 3)) { sampledKept++; reason = "sample"; }
        else { if (error || slow || sampled) tracesDropped++; return; }
        tracesKept++;

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("traceId", traceId);
        t.put("at", start / 1_000_000);
        t.put("name", cut(masker.mask(rootName(root), 300), 300));
        t.put("service", cut(root.service(), 120));
        t.put("durationMs", durMs);
        t.put("error", error);
        t.put("reason", reason);
        Object status = root.attrs().get("http.response.status_code") != null ? root.attrs().get("http.response.status_code") : root.attrs().get("http.status_code");
        if (status instanceof Number n) t.put("status", n.intValue());
        String rid = requestId(spans);
        if (rid != null) t.put("requestId", rid);
        t.put("spanCount", spans.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (OtlpDecoder.Span s : spans) {
            if (out.size() >= MAX_SPANS_PER_TRACE) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.spanId());
            if (s.parentId() != null) m.put("parent", s.parentId());
            m.put("name", cut(masker.mask(s.name(), 200), 200));
            m.put("kind", s.kind());
            m.put("startUs", Math.max(0, (s.startNs() - start) / 1000));
            m.put("durUs", s.durationUs());
            if (failed(s)) m.put("error", true);
            DepKey k = classify(s);
            if (k != null) { m.put("dep", k.kind()); m.put("target", k.target()); }
            Map<String, Object> attrs = attrs(s);
            if (!attrs.isEmpty()) m.put("attrs", attrs);
            out.add(m);
        }
        t.put("spans", out);
        kept.add(t);
    }

    /** Traces finished since the last batch. */
    public synchronized List<Map<String, Object>> drainTraces() {
        List<Map<String, Object>> out = new ArrayList<>(kept);
        kept.clear();
        return out;
    }

    /** Counts since the last call, for the agent-overhead figures. */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spansReceived", received);
        m.put("spansDropped", dropped);
        m.put("tracesKept", tracesKept);
        m.put("tracesDropped", tracesDropped);
        m.put("tracesPending", pending.size());
        m.put("spansPending", pendingSpans);
        received = dropped = tracesKept = tracesDropped = 0;
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static final String[] KEEP = {
            "http.request.method", "http.method", "http.route", "url.path", "http.target", "url.full", "http.url",
            "http.response.status_code", "http.status_code", "server.address", "server.port", "net.peer.name",
            "db.system", "db.namespace", "db.name", "db.operation.name", "db.operation", "db.query.text", "db.statement",
            "messaging.system", "messaging.destination.name", "messaging.operation.type",
            "exception.type", "exception.message", "error.type", "code.function", "code.namespace", "rpc.system", "rpc.service", "rpc.method"};

    private Map<String, Object> attrs(OtlpDecoder.Span s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : KEEP) {
            Object v = s.attrs().get(k);
            if (v == null) continue;
            if (v instanceof String str) {
                if (k.equals("db.statement") || k.equals("db.query.text")) str = sanitizeSql(str);
                else if (k.equals("url.full") || k.equals("http.url") || k.equals("http.target")) str = str.replaceAll("\\?.*$", "");
                v = masker.mask(str, k.startsWith("db.") ? 1000 : 400);
            }
            out.put(k, v);
        }
        if (s.statusMessage() != null && !s.statusMessage().isBlank()) out.put("status.message", masker.mask(s.statusMessage(), 400));
        return out;
    }

    private static final Pattern SQL_STRING = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'");
    private static final Pattern SQL_NUMBER = Pattern.compile("(?<![\\w$.])-?\\d+(?:\\.\\d+)?(?![\\w$])");
    private static final Pattern SQL_IN_LIST = Pattern.compile("(?i)\\bIN\\s*\\((?:\\s*\\?\\s*,)+\\s*\\?\\s*\\)");
    private static final Pattern SQL_TABLE = Pattern.compile("(?is)^\\s*(select|insert|update|delete|replace|merge|upsert)\\b.*?\\b(?:from|into|update|join)\\s+([`\"\\[]?[\\w.]+[`\"\\]]?)");
    private static final Pattern SQL_UPDATE = Pattern.compile("(?is)^\\s*update\\s+([`\"\\[]?[\\w.]+[`\"\\]]?)");

    /** Literal values out of SQL, so customer data never travels: 'abc' and 42 become ?. */
    static String sanitizeSql(String sql) {
        String s = SQL_STRING.matcher(sql).replaceAll("?");
        s = SQL_NUMBER.matcher(s).replaceAll("?");
        return SQL_IN_LIST.matcher(s).replaceAll("IN (?)").replaceAll("\\s+", " ").trim();
    }

    /** "SELECT users", from db.operation and the table, or from the statement. */
    static String sqlSummary(OtlpDecoder.Span s) {
        Map<String, Object> a = s.attrs();
        String stmt = str(a.get("db.query.text"), a.get("db.statement"));
        if (stmt != null && stmt.length() > 400) stmt = stmt.substring(0, 400);   // the verb and first table are near the start
        String op = str(a.get("db.operation.name"), a.get("db.operation"));
        String table = str(a.get("db.collection.name"), a.get("db.sql.table"));
        if (stmt != null && (op == null || table == null)) {
            Matcher u = SQL_UPDATE.matcher(stmt);
            Matcher m = SQL_TABLE.matcher(stmt);
            if (u.find()) { op = op == null ? "UPDATE" : op; table = table == null ? u.group(1) : table; }
            else if (m.find()) { op = op == null ? m.group(1) : op; table = table == null ? m.group(2) : table; }
            else if (op == null) op = stmt.trim().split("\\s+", 2)[0];
        }
        if (op == null) return s.name() == null ? "" : sanitizeSql(s.name());
        return (op.toUpperCase(Locale.ROOT) + (table != null ? " " + table.replaceAll("[`\"\\[\\]]", "") : "")).trim();
    }

    private String rootName(OtlpDecoder.Span r) {
        String method = str(r.attrs().get("http.request.method"), r.attrs().get("http.method"));
        String route = str(r.attrs().get("http.route"));
        if (method != null && route != null) return method + " " + route;
        if (method != null) {
            String path = str(r.attrs().get("url.path"), r.attrs().get("http.target"));
            if (path != null) return method + " " + routes.normalize(path.replaceAll("\\?.*$", ""));
        }
        return r.name();
    }

    private static String requestId(List<OtlpDecoder.Span> spans) {
        for (OtlpDecoder.Span s : spans)
            for (var e : s.attrs().entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if ((k.endsWith("x_request_id") || k.endsWith("x-request-id") || k.equals("request_id") || k.equals("requestid")) && e.getValue() != null) {
                    String v = String.valueOf(e.getValue()).replaceAll("[\\[\\]\" ]", "");
                    if (v.matches("[0-9A-Za-z-]{6,64}")) return v;
                }
            }
        return null;
    }

    static boolean failed(OtlpDecoder.Span s) {
        if (s.error()) return true;
        Object st = s.attrs().get("http.response.status_code") != null ? s.attrs().get("http.response.status_code") : s.attrs().get("http.status_code");
        return st instanceof Number n && n.intValue() >= 500;
    }

    /** The same trace is kept or not on every agent: decided by its id, not by chance. */
    static boolean sampled(String traceId, double ratio) {
        if (ratio <= 0) return false;
        if (ratio >= 1) return true;
        long h = 1125899906842597L;
        for (int i = 0; i < traceId.length(); i++) h = 31 * h + traceId.charAt(i);
        return Math.floorMod(h, 10_000) < ratio * 10_000;
    }

    private static String str(Object... vs) {
        for (Object v : vs) if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        return null;
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
