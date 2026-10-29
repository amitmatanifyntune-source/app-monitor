package com.fyntune.agent;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the app's checks on their intervals, from inside the client's network:
 * <ul>
 *   <li>HTTP: url, expectStatus (default 200), maxMs (default 3000), contains (text the body must contain), and for
 *       https the certificate's days left (sslWarnDays, default 14).</li>
 *   <li>SQL: sql (one SELECT or WITH), refColumn (sent, up to 10 values), warnAbove (fails when more rows than this,
 *       default 0). Runs on a read-only connection with a 10 s timeout. Only the row count and the reference values
 *       are sent, never rows.</li>
 * </ul>
 * Checks come from agent.json and the server. SQL checks from the server run only with allowServerSqlChecks.
 */
public final class Checks {
    public static final int SQL_TIMEOUT_SECONDS = 10, SQL_MAX_ROWS = 10_000;

    private final HttpClient http;
    private final AgentConfig.Db db;
    private final Masker masker;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "check"); t.setDaemon(true); return t; });
    private final Map<String, Instant> lastRun = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Queue<Map<String, Object>> results = new ConcurrentLinkedQueue<>();
    private volatile List<Map<String, Object>> checks = List.of();

    public Checks(HttpClient http, AgentConfig.Db db, Masker masker) {
        this.http = http;
        this.db = db;
        this.masker = masker;
    }

    /** Local checks always; server checks too, but its SQL ones only when allowed in agent.json. */
    public void setChecks(List<Map<String, Object>> local, List<Map<String, Object>> remote, boolean allowServerSql) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (remote != null) for (Map<String, Object> c : remote) {
            if ("SQL".equalsIgnoreCase(String.valueOf(c.get("kind"))) && !allowServerSql) continue;
            if (c.get("name") != null) byName.put(String.valueOf(c.get("name")), c);
        }
        // a local check with the same name wins
        if (local != null) for (Map<String, Object> c : local) if (c.get("name") != null) byName.put(String.valueOf(c.get("name")), c);
        checks = List.copyOf(byName.values());
    }

    public List<Map<String, Object>> current() { return checks; }

    /** Starts every check that is due. Called every few seconds. */
    public void tick() {
        Instant now = Instant.now();
        for (Map<String, Object> c : checks) {
            String name = String.valueOf(c.get("name"));
            int every = Math.max(15, intOf(c.get("intervalSec"), "SQL".equalsIgnoreCase(String.valueOf(c.get("kind"))) ? 300 : 60));
            Instant last = lastRun.get(name);
            if (last != null && last.plusSeconds(every).isAfter(now)) continue;
            if (!running.add(name)) continue;
            lastRun.put(name, now);
            pool.submit(() -> {
                try { results.add(run(c)); } finally { running.remove(name); }
            });
        }
    }

    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> r;
        while ((r = results.poll()) != null) out.add(r);
        return out;
    }

    Map<String, Object> run(Map<String, Object> c) {
        String kind = String.valueOf(c.get("kind")).toUpperCase(Locale.ROOT);
        try {
            return "SQL".equals(kind) ? sql(c) : http(c);
        } catch (Exception e) {
            return result(c, false, null, null, masker.mask(e.getClass().getSimpleName() + ": " + e.getMessage(), 500), List.of());
        }
    }

    private Map<String, Object> http(Map<String, Object> c) throws Exception {
        String url = String.valueOf(c.get("url"));
        int expect = intOf(c.get("expectStatus"), 200), maxMs = intOf(c.get("maxMs"), 3000), sslWarn = intOf(c.get("sslWarnDays"), 14);
        String contains = c.get("contains") == null ? null : String.valueOf(c.get("contains"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(Math.max(maxMs * 3L, 5000)))
                .header("User-Agent", "fyntune-agent/" + Main.VERSION).GET().build();
        long t0 = System.nanoTime();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return result(c, false, (System.nanoTime() - t0) / 1_000_000, null, "No answer within " + Math.max(maxMs * 3L, 5000) + " ms", List.of());
        } catch (java.net.ConnectException e) {
            return result(c, false, (System.nanoTime() - t0) / 1_000_000, null, "Connection refused: is the app running?", List.of());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        List<String> problems = new ArrayList<>();
        if (res.statusCode() != expect) problems.add("HTTP " + res.statusCode() + " (expected " + expect + ")");
        if (ms > maxMs) problems.add("slow: " + ms + " ms (limit " + maxMs + " ms)");
        if (contains != null && (res.body() == null || !res.body().contains(contains))) problems.add("response does not contain \"" + contains + "\"");
        Double daysLeft = null;
        Optional<SSLSession> ssl = res.sslSession();
        if (ssl.isPresent()) {
            try {
                if (ssl.get().getPeerCertificates()[0] instanceof X509Certificate x) {
                    daysLeft = (double) Duration.between(Instant.now(), x.getNotAfter().toInstant()).toDays();
                    if (daysLeft < sslWarn) problems.add("certificate expires in " + daysLeft.longValue() + " days");
                }
            } catch (Exception ignored) { }
        }
        String msg = problems.isEmpty() ? res.statusCode() + " in " + ms + " ms" + (daysLeft == null ? "" : " · certificate " + daysLeft.longValue() + " days left")
                : String.join(" · ", problems);
        return result(c, problems.isEmpty(), ms, daysLeft, msg, List.of());
    }

    private Map<String, Object> sql(Map<String, Object> c) throws Exception {
        if (db == null || db.url() == null) return result(c, false, null, null, "No database is configured in agent.json", List.of());
        String sql = String.valueOf(c.get("sql")).trim();
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with")) || sql.contains(";"))
            return result(c, false, null, null, "Refused: a SQL check must be one SELECT or WITH query", List.of());
        String refColumn = c.get("refColumn") == null ? null : String.valueOf(c.get("refColumn"));
        double warnAbove = c.get("warnAbove") instanceof Number n ? n.doubleValue() : 0;
        long t0 = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(db.url(), db.user(), db.password())) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                // a read-only transaction as well, so even a user with write grants cannot change data here
                if (db.url().startsWith("jdbc:postgresql")) s.execute("SET TRANSACTION READ ONLY");
                else if (db.url().startsWith("jdbc:mysql") || db.url().startsWith("jdbc:mariadb")) s.execute("START TRANSACTION READ ONLY");
                s.setQueryTimeout(SQL_TIMEOUT_SECONDS);
                s.setMaxRows(SQL_MAX_ROWS);
                long count = 0;
                List<String> refs = new ArrayList<>();
                try (ResultSet rs = s.executeQuery(sql)) {
                    int col = -1;
                    if (refColumn != null) for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                        if (rs.getMetaData().getColumnLabel(i).equalsIgnoreCase(refColumn)) col = i;
                    while (rs.next()) {
                        count++;
                        if (col > 0 && refs.size() < 10) refs.add(masker.mask(String.valueOf(rs.getObject(col)), 80));
                    }
                }
                conn.rollback();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                boolean ok = count <= warnAbove;
                String msg = count + " row" + (count == 1 ? "" : "s") + (count >= SQL_MAX_ROWS ? " or more" : "") + (ok ? "" : " (limit " + fmt(warnAbove) + ")");
                return result(c, ok, ms, (double) count, msg, refs);
            }
        }
    }

    private static Map<String, Object> result(Map<String, Object> c, boolean ok, Long ms, Double value, String message, List<String> refs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", c.get("name"));
        r.put("kind", String.valueOf(c.get("kind")).toUpperCase(Locale.ROOT));
        r.put("at", System.currentTimeMillis());
        r.put("ok", ok);
        r.put("ms", ms);
        r.put("value", value);
        r.put("message", message);
        r.put("refs", refs);
        return r;
    }

    static int intOf(Object o, int d) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? d : Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return d; }
    }

    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    public void shutdown() { pool.shutdownNow(); }
}
