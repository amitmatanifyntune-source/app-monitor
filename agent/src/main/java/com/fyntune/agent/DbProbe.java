package com.fyntune.agent;

import java.sql.*;
import java.util.*;

/**
 * Reads a database's own statistics with a read-only monitoring user: reachability and response time, size,
 * connections, cache hit rate, locks waiting, queries running now, the heaviest statements and the largest tables.
 * It never reads application data. Both engines produce the same snapshot shape, so the screens are the same:
 *
 * <pre>{summary, warn, reasons[], pingMs, sizeMb, connections, maxConnections, connectionStates{}, cacheHitPct,
 *  deadlocks, locksWaiting, running[{id,user,state,ms,wait,query}], statements{available,note,rows[]}, tables[]}</pre>
 *
 * Query text is masked before it is sent: running queries can carry literal values.
 */
public abstract class DbProbe implements AutoCloseable {
    public record Snapshot(String engine, String version, Map<String, Object> data) {}

    protected final AgentConfig.Db cfg;
    protected final Masker masker;
    private Connection conn;
    private Map<String, Object> spec;
    private long specAt;

    protected DbProbe(AgentConfig.Db cfg, Masker masker) {
        this.cfg = cfg;
        this.masker = masker;
    }

    public static DbProbe of(AgentConfig.Db cfg, Masker masker) {
        if (cfg == null || cfg.url() == null) return null;
        String e = cfg.engine() == null ? (cfg.url().startsWith("jdbc:mysql") ? "mysql" : "postgres") : cfg.engine().toLowerCase(Locale.ROOT);
        return switch (e) {
            case "mysql", "mariadb" -> new Mysql(cfg, masker);
            case "postgres", "postgresql" -> new Postgres(cfg, masker);
            default -> throw new IllegalArgumentException("db.engine must be postgres or mysql, not " + cfg.engine());
        };
    }

    public abstract String engine();

    /** A read-only connection, opened again if it was lost. */
    protected synchronized Connection connection() throws SQLException {
        if (conn != null) {
            try { if (conn.isValid(3)) return conn; } catch (SQLException ignored) { }
            try { conn.close(); } catch (SQLException ignored) { }
        }
        DriverManager.setLoginTimeout(10);
        conn = DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password());
        conn.setReadOnly(true);
        conn.setAutoCommit(true);
        return conn;
    }

    /** Takes a snapshot. A database that cannot be reached gives a snapshot that says so (and why). */
    public Snapshot snapshot() {
        Map<String, Object> d = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        String version = null;
        try {
            Connection c = connection();
            long t0 = System.nanoTime();
            try (Statement s = c.createStatement()) { s.setQueryTimeout(10); s.execute("SELECT 1"); }
            long ping = (System.nanoTime() - t0) / 1_000_000;
            d.put("up", true);
            d.put("pingMs", ping);
            version = collect(c, d, reasons);
            d.put("spec", spec(c));
            if (ping > 200) reasons.add("Database answers slowly (" + ping + " ms)");
        } catch (SQLException e) {
            d.put("up", false);
            reasons.add("Database not reachable: " + masker.mask(e.getMessage(), 300));
        }
        d.put("reasons", reasons);
        d.put("warn", !reasons.isEmpty());
        d.put("summary", summary(d, reasons));
        return new Snapshot(engine(), version, d);
    }

    private static String summary(Map<String, Object> d, List<String> reasons) {
        if (!Boolean.TRUE.equals(d.get("up"))) return String.join(" · ", reasons);
        List<String> parts = new ArrayList<>();
        if (d.get("sizeMb") != null) parts.add(d.get("sizeMb") + " MB");
        if (d.get("connections") != null) parts.add(d.get("connections") + (d.get("maxConnections") != null ? " of " + d.get("maxConnections") : "") + " connections");
        if (d.get("cacheHitPct") != null) parts.add("cache hit " + d.get("cacheHitPct") + "%");
        parts.add("ping " + d.get("pingMs") + " ms");
        if (!reasons.isEmpty()) parts.add(String.join(" · ", reasons));
        return String.join(" · ", parts);
    }

    /** The database server's settings and where it runs; read hourly, since they change only on a restart or a reconfiguration. */
    private Map<String, Object> spec(Connection c) {
        if (spec != null && System.currentTimeMillis() - specAt < 3_600_000) return spec;
        Map<String, Object> s = new LinkedHashMap<>();
        // host and port from the JDBC address only; anything after them (options, a password) is never read
        var mt = java.util.regex.Pattern.compile("^jdbc:[a-z]+://([^/:?,;\\[]+|\\[[^]]+])(?::(\\d+))?").matcher(cfg.url());
        if (mt.find()) {
            String host = mt.group(1);
            s.put("host", host);
            if (mt.group(2) != null) s.put("port", Integer.parseInt(mt.group(2)));
            s.put("sameServer", host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        }
        try {
            specify(c, s);
        } catch (SQLException e) {
            s.put("note", "Some settings could not be read: " + masker.mask(e.getMessage(), 200));
        }
        spec = s;
        specAt = System.currentTimeMillis();
        return s;
    }

    /** Adds this engine's version, role and main settings to s. */
    protected abstract void specify(Connection c, Map<String, Object> s) throws SQLException;

    protected static String size(long bytes) {
        if (bytes >= 1L << 30) return round1(bytes / (double) (1L << 30)) + " GB";
        if (bytes >= 1L << 20) return (bytes >> 20) + " MB";
        return (bytes >> 10) + " kB";
    }

    /** Fills d with this engine's statistics and returns the server version. */
    protected abstract String collect(Connection c, Map<String, Object> d, List<String> reasons) throws SQLException;

    protected List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof java.math.BigDecimal b) v = b.doubleValue();
                        else if (v instanceof java.math.BigInteger b) v = b.longValue();
                        else if (v instanceof java.sql.Timestamp t) v = t.toInstant().toString();
                        else if (v instanceof java.time.OffsetDateTime t) v = t.toString();
                        else if (v != null && !(v instanceof Number) && !(v instanceof Boolean)) v = v.toString();
                        m.put(md.getColumnLabel(i), v);
                    }
                    out.add(m);
                }
                return out;
            }
        }
    }

    protected Map<String, Object> row(Connection c, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> r = rows(c, sql, args);
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    protected static long num(Object o) { return o instanceof Number n ? n.longValue() : o == null ? 0 : Long.parseLong(o.toString().trim()); }
    protected static double round1(double v) { return Math.round(v * 10) / 10.0; }

    protected void maskQueries(List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) if (r.get("query") instanceof String q) r.put("query", masker.mask(q, 1500));
    }

    @Override
    public synchronized void close() {
        if (conn != null) try { conn.close(); } catch (SQLException ignored) { }
        conn = null;
    }

    // ================================================================== PostgreSQL

    /** Needs a user with the pg_monitor role. Statement statistics need the pg_stat_statements extension. */
    static final class Postgres extends DbProbe {
        Postgres(AgentConfig.Db cfg, Masker masker) { super(cfg, masker); }

        @Override public String engine() { return "postgres"; }

        @Override
        protected void specify(Connection c, Map<String, Object> s) throws SQLException {
            Map<String, Object> r = row(c, """
                    SELECT version() AS full, pg_postmaster_start_time() AS started, pg_is_in_recovery() AS replica, current_database() AS db,
                           (SELECT count(*) FROM pg_database WHERE NOT datistemplate) AS databases,
                           (SELECT string_agg(extname || ' ' || extversion, ', ' ORDER BY extname) FROM pg_extension WHERE extname <> 'plpgsql') AS extensions""");
            s.put("product", "PostgreSQL");
            s.put("versionFull", r.get("full"));
            s.put("startedAt", r.get("started"));
            s.put("role", Boolean.TRUE.equals(r.get("replica")) ? "Replica (read-only standby)" : "Primary");
            s.put("database", r.get("db"));
            s.put("databases", r.get("databases"));
            if (r.get("extensions") != null) s.put("extensions", r.get("extensions"));
            Map<String, Object> set = new LinkedHashMap<>();
            for (Map<String, Object> x : rows(c, """
                    SELECT name, current_setting(name) AS v FROM pg_settings
                     WHERE name IN ('max_connections', 'shared_buffers', 'effective_cache_size', 'work_mem', 'maintenance_work_mem', 'wal_level',
                                    'max_wal_size', 'max_worker_processes', 'max_parallel_workers', 'autovacuum', 'ssl', 'server_encoding',
                                    'TimeZone', 'shared_preload_libraries', 'data_checksums', 'synchronous_commit', 'statement_timeout')
                     ORDER BY name"""))
                set.put(String.valueOf(x.get("name")), x.get("v"));
            s.put("settings", set);
        }

        @Override
        protected String collect(Connection c, Map<String, Object> d, List<String> reasons) throws SQLException {
            String version = String.valueOf(row(c, "SHOW server_version").values().iterator().next());
            Map<String, Object> s = row(c, """
                    SELECT pg_database_size(current_database()) / 1048576 AS size_mb, numbackends, xact_commit, xact_rollback, deadlocks, temp_bytes / 1048576 AS temp_mb,
                           CASE WHEN blks_hit + blks_read = 0 THEN 100 ELSE 100.0 * blks_hit / (blks_hit + blks_read) END AS hit
                      FROM pg_stat_database WHERE datname = current_database()""");
            long commits = num(s.get("xact_commit"));
            double hit = round1(((Number) s.get("hit")).doubleValue());
            d.put("sizeMb", num(s.get("size_mb")));
            d.put("connections", num(s.get("numbackends")));
            d.put("maxConnections", num(row(c, "SELECT setting::int AS v FROM pg_settings WHERE name = 'max_connections'").get("v")));
            d.put("cacheHitPct", hit);
            d.put("commits", commits);
            d.put("rollbacks", num(s.get("xact_rollback")));
            d.put("deadlocks", num(s.get("deadlocks")));
            d.put("tempMb", num(s.get("temp_mb")));
            Map<String, Object> states = new LinkedHashMap<>();
            for (Map<String, Object> r : rows(c, "SELECT coalesce(state, 'background') AS s, count(*) AS n FROM pg_stat_activity WHERE datname = current_database() GROUP BY 1 ORDER BY 2 DESC"))
                states.put(String.valueOf(r.get("s")), r.get("n"));
            d.put("connectionStates", states);
            long locks = num(row(c, "SELECT count(*) AS n FROM pg_locks WHERE NOT granted").get("n"));
            d.put("locksWaiting", locks);

            List<Map<String, Object>> running = rows(c, """
                    SELECT pid AS id, usename AS "user", state, (EXTRACT(EPOCH FROM (now() - coalesce(query_start, backend_start))) * 1000)::bigint AS ms,
                           wait_event_type AS wait, query
                      FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND state <> 'idle'
                     ORDER BY ms DESC LIMIT 20""");
            maskQueries(running);
            d.put("running", running);

            Map<String, Object> st = new LinkedHashMap<>();
            try {
                // the application's queries in this database: not this monitoring user's own, nor catalog or BEGIN/SET
                List<Map<String, Object>> top = rows(c, """
                        SELECT query, calls, round(total_exec_time::numeric, 1) AS "totalMs", round(mean_exec_time::numeric, 1) AS "meanMs",
                               round(max_exec_time::numeric, 1) AS "maxMs", rows,
                               round((100 * total_exec_time / nullif(sum(total_exec_time) OVER (), 0))::numeric, 1) AS pct
                          FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND userid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
                           AND query ~* '^\\s*(select|insert|update|delete|with)\\M' AND query NOT ILIKE '%pg_catalog.%'
                         ORDER BY total_exec_time DESC LIMIT 30""");
                maskQueries(top);
                st.put("available", true);
                st.put("rows", top);
            } catch (SQLException e) {
                st.put("available", false);
                st.put("note", "Statement statistics are not available: add pg_stat_statements to shared_preload_libraries and run CREATE EXTENSION pg_stat_statements.");
                st.put("rows", List.of());
            }
            d.put("statements", st);

            List<Map<String, Object>> tables = rows(c, """
                    SELECT relname AS "table", n_live_tup AS rows, pg_total_relation_size(relid) / 1024 AS "sizeKb", seq_scan AS "seqScans",
                           coalesce(idx_scan, 0) AS "idxScans", n_tup_ins + n_tup_upd + n_tup_del AS writes, n_dead_tup AS "deadRows"
                      FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 30""");
            for (Map<String, Object> t : tables) {
                long rows = num(t.get("rows")), seq = num(t.get("seqScans")), idx = num(t.get("idxScans")), dead = num(t.get("deadRows"));
                if (rows >= 5000 && seq > 50 && seq > idx) t.put("hint", "Mostly read by full scans: check the queries' filters have an index");
                else if (dead > 1000 && dead > rows / 5) t.put("hint", "Many dead rows: vacuum is behind");
            }
            d.put("tables", tables);

            long conns = num(d.get("connections")), max = num(d.get("maxConnections"));
            if (max > 0 && conns * 100 / max >= 85) reasons.add(conns + " of " + max + " connections in use");
            if (locks > 0) reasons.add(locks + " lock" + (locks == 1 ? "" : "s") + " waiting");
            if (hit < 90 && commits > 1000) reasons.add("Cache hit rate " + Math.round(hit) + "% (memory may be short)");
            long longRunning = running.stream().filter(r -> num(r.get("ms")) > 30_000 && !"idle in transaction".equals(r.get("state"))).count();
            if (longRunning > 0) reasons.add(longRunning + " quer" + (longRunning == 1 ? "y" : "ies") + " running for over 30 s");
            return version;
        }
    }

    // ================================================================== MySQL

    /**
     * Needs a user with PROCESS and SELECT on performance_schema (see README). Statement statistics need
     * performance_schema=ON, the default from MySQL 5.6.6. Lock waits use performance_schema.data_lock_waits (8.0).
     */
    static final class Mysql extends DbProbe {
        Mysql(AgentConfig.Db cfg, Masker masker) { super(cfg, masker); }

        @Override public String engine() { return "mysql"; }

        private static final Set<String> BYTES = Set.of("innodb_buffer_pool_size", "innodb_redo_log_capacity", "innodb_log_file_size", "max_allowed_packet",
                "tmp_table_size", "max_heap_table_size", "key_buffer_size");

        @Override
        protected void specify(Connection c, Map<String, Object> s) throws SQLException {
            Map<String, Object> v = new TreeMap<>();
            for (Map<String, Object> r : rows(c, """
                    SHOW GLOBAL VARIABLES WHERE Variable_name IN ('version', 'version_comment', 'version_compile_os', 'version_compile_machine', 'hostname',
                      'port', 'max_connections', 'innodb_buffer_pool_size', 'innodb_redo_log_capacity', 'innodb_log_file_size', 'max_allowed_packet',
                      'tmp_table_size', 'max_heap_table_size', 'key_buffer_size', 'table_open_cache', 'thread_cache_size', 'character_set_server',
                      'collation_server', 'time_zone', 'system_time_zone', 'read_only', 'super_read_only', 'log_bin', 'binlog_format', 'server_id',
                      'slow_query_log', 'long_query_time', 'have_ssl', 'innodb_flush_log_at_trx_commit', 'transaction_isolation')""")) {
                String name = String.valueOf(r.getOrDefault("Variable_name", r.get("VARIABLE_NAME")));
                Object val = r.getOrDefault("Value", r.get("VARIABLE_VALUE"));
                if (BYTES.contains(name)) try { val = size(Long.parseLong(String.valueOf(val))); } catch (NumberFormatException ignored) { }
                v.put(name, val);
            }
            s.put("product", String.valueOf(v.getOrDefault("version_comment", "MySQL")));
            s.put("versionFull", v.get("version") + " on " + v.get("version_compile_os") + " " + v.get("version_compile_machine"));
            if (v.get("hostname") != null) s.put("serverHostname", v.remove("hostname"));
            for (Map<String, Object> r : rows(c, "SHOW GLOBAL STATUS LIKE 'Uptime'"))
                s.put("startedAt", java.time.Instant.now().minusSeconds(num(r.getOrDefault("Value", r.get("VARIABLE_VALUE")))).toString());
            s.put("role", "ON".equalsIgnoreCase(String.valueOf(v.get("read_only"))) || "ON".equalsIgnoreCase(String.valueOf(v.get("super_read_only")))
                    ? "Read-only (likely a replica)" : "Read-write (primary)");
            s.put("database", row(c, "SELECT DATABASE() AS d").get("d"));
            for (String k : List.of("version", "version_comment", "version_compile_os", "version_compile_machine")) v.remove(k);
            s.put("settings", v);
        }

        @Override
        protected String collect(Connection c, Map<String, Object> d, List<String> reasons) throws SQLException {
            String version = String.valueOf(row(c, "SELECT VERSION() AS v").get("v"));
            Map<String, Long> st = new HashMap<>();
            for (Map<String, Object> r : rows(c, """
                    SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected', 'Threads_running', 'Uptime', 'Slow_queries', 'Questions',
                      'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads', 'Innodb_row_lock_current_waits', 'Aborted_connects')""")) {
                Object name = r.getOrDefault("Variable_name", r.get("VARIABLE_NAME")), val = r.getOrDefault("Value", r.get("VARIABLE_VALUE"));
                try { st.put(String.valueOf(name), Long.parseLong(String.valueOf(val))); } catch (NumberFormatException ignored) { }
            }
            long maxConn = 0;
            for (Map<String, Object> r : rows(c, "SHOW VARIABLES LIKE 'max_connections'"))
                maxConn = num(r.getOrDefault("Value", r.get("VARIABLE_VALUE")));
            long reqs = st.getOrDefault("Innodb_buffer_pool_read_requests", 0L), reads = st.getOrDefault("Innodb_buffer_pool_reads", 0L);
            double hit = reqs == 0 ? 100 : round1(100.0 * (reqs - reads) / reqs);
            d.put("sizeMb", num(row(c, "SELECT coalesce(sum(data_length + index_length), 0) DIV 1048576 AS mb FROM information_schema.TABLES WHERE table_schema = DATABASE()").get("mb")));
            d.put("connections", st.getOrDefault("Threads_connected", 0L));
            d.put("maxConnections", maxConn);
            d.put("threadsRunning", st.getOrDefault("Threads_running", 0L));
            d.put("uptimeSeconds", st.getOrDefault("Uptime", 0L));
            d.put("slowQueries", st.getOrDefault("Slow_queries", 0L));
            d.put("questions", st.getOrDefault("Questions", 0L));
            d.put("abortedConnects", st.getOrDefault("Aborted_connects", 0L));
            d.put("cacheHitPct", hit);

            long locks;
            try {
                locks = num(row(c, "SELECT count(*) AS n FROM performance_schema.data_lock_waits").get("n"));
            } catch (SQLException e) {
                locks = st.getOrDefault("Innodb_row_lock_current_waits", 0L);   // MySQL 5.7
            }
            d.put("locksWaiting", locks);
            try {
                d.put("deadlocks", num(row(c, "SELECT count AS n FROM information_schema.INNODB_METRICS WHERE name = 'lock_deadlocks'").get("n")));
            } catch (SQLException ignored) { }

            Map<String, Object> states = new LinkedHashMap<>();
            for (Map<String, Object> r : rows(c, "SELECT COMMAND AS s, count(*) AS n FROM information_schema.PROCESSLIST GROUP BY COMMAND ORDER BY n DESC"))
                states.put(String.valueOf(r.get("s")), r.get("n"));
            d.put("connectionStates", states);

            List<Map<String, Object>> running = rows(c, """
                    SELECT ID AS id, USER AS user, COALESCE(STATE, COMMAND) AS state, TIME * 1000 AS ms, COMMAND AS wait, INFO AS query
                      FROM information_schema.PROCESSLIST
                     WHERE COMMAND NOT IN ('Sleep', 'Daemon', 'Binlog Dump') AND ID <> CONNECTION_ID()
                     ORDER BY TIME DESC LIMIT 20""");
            maskQueries(running);
            d.put("running", running);

            Map<String, Object> stm = new LinkedHashMap<>();
            try {
                // timers are in picoseconds
                List<Map<String, Object>> top = rows(c, """
                        SELECT DIGEST_TEXT AS query, COUNT_STAR AS calls, ROUND(SUM_TIMER_WAIT / 1e9, 1) AS totalMs, ROUND(AVG_TIMER_WAIT / 1e9, 1) AS meanMs,
                               ROUND(MAX_TIMER_WAIT / 1e9, 1) AS maxMs, SUM_ROWS_SENT + SUM_ROWS_AFFECTED AS `rows`,
                               ROUND(100 * SUM_TIMER_WAIT / NULLIF((SELECT SUM(SUM_TIMER_WAIT) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME = DATABASE()), 0), 1) AS pct
                          FROM performance_schema.events_statements_summary_by_digest
                         WHERE SCHEMA_NAME = DATABASE() AND DIGEST_TEXT IS NOT NULL
                         ORDER BY SUM_TIMER_WAIT DESC LIMIT 30""");
                maskQueries(top);
                stm.put("available", true);
                stm.put("rows", top);
            } catch (SQLException e) {
                stm.put("available", false);
                stm.put("note", "Statement statistics are not available: turn on performance_schema and grant SELECT on performance_schema.* to the monitoring user.");
                stm.put("rows", List.of());
            }
            d.put("statements", stm);

            List<Map<String, Object>> tables = rows(c, """
                    SELECT TABLE_NAME AS `table`, TABLE_ROWS AS `rows`, (DATA_LENGTH + INDEX_LENGTH) DIV 1024 AS sizeKb, DATA_FREE DIV 1024 AS freeKb, ENGINE AS engine
                      FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                     ORDER BY DATA_LENGTH + INDEX_LENGTH DESC LIMIT 30""");
            for (Map<String, Object> t : tables) {
                long size = num(t.get("sizeKb")), free = num(t.get("freeKb"));
                if (size > 100_000 && free > size / 2) t.put("hint", "Much unused space: OPTIMIZE TABLE may reclaim it");
                if (!"InnoDB".equalsIgnoreCase(String.valueOf(t.get("engine")))) t.put("hint", "Not InnoDB: no row-level locking or crash safety");
            }
            d.put("tables", tables);

            long conns = num(d.get("connections"));
            if (maxConn > 0 && conns * 100 / maxConn >= 85) reasons.add(conns + " of " + maxConn + " connections in use");
            if (locks > 0) reasons.add(locks + " lock" + (locks == 1 ? "" : "s") + " waiting");
            if (hit < 90 && reqs > 100_000) reasons.add("Buffer pool hit rate " + Math.round(hit) + "% (innodb_buffer_pool_size may be small)");
            long longRunning = running.stream().filter(r -> num(r.get("ms")) > 30_000).count();
            if (longRunning > 0) reasons.add(longRunning + " quer" + (longRunning == 1 ? "y" : "ies") + " running for over 30 s");
            return version;
        }
    }
}
