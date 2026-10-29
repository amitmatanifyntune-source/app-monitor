package com.fyntune.monitor.apps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.auth.Users;
import com.fyntune.monitor.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/** Apps, their agents and checks, and the users who may see them. Administrators only. */
@Service
public class AdminService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Audit audit;
    private final Users users;
    private final PasswordEncoder encoder;

    public AdminService(JdbcTemplate jdbc, ObjectMapper json, Audit audit, Users users, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.users = users;
        this.encoder = encoder;
    }

    // ------------------------------------------------------------------ apps

    public record AppIn(String code, String name, String environment, String description, Integer slowMs, List<String> mask, List<Map<String, String>> routeRules) {}

    public Map<String, Object> createApp(AppIn in) {
        if (in.code() == null || !CODE.matcher(in.code()).matches())
            throw ApiException.bad("Code: 2–40 lower-case letters, digits or hyphens, e.g. proton-core");
        if (in.name() == null || in.name().isBlank()) throw ApiException.bad("Name is required");
        validatePatterns(in.mask(), in.routeRules());
        jdbc.update("INSERT INTO app (code, name, environment, description, slow_ms, mask, route_rules) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                in.code(), in.name().trim(), blankTo(in.environment(), "production"), in.description(), slow(in.slowMs()),
                write(in.mask() == null ? List.of() : in.mask()), write(in.routeRules() == null ? List.of() : in.routeRules()));
        audit.log("APP_CREATED", in.code(), in.name());
        return app(in.code());
    }

    public Map<String, Object> updateApp(String code, AppIn in) {
        long id = appId(code);
        validatePatterns(in.mask(), in.routeRules());
        jdbc.update("""
                UPDATE app SET name = coalesce(?, name), environment = coalesce(?, environment), description = coalesce(?, description),
                       slow_ms = coalesce(?, slow_ms), mask = coalesce(?::jsonb, mask), route_rules = coalesce(?::jsonb, route_rules) WHERE id = ?""",
                in.name(), in.environment(), in.description(), in.slowMs() == null ? null : slow(in.slowMs()),
                in.mask() == null ? null : write(in.mask()), in.routeRules() == null ? null : write(in.routeRules()), id);
        audit.log("APP_UPDATED", code, null);
        return app(code);
    }

    public void deleteApp(String code) {
        long id = appId(code);
        jdbc.update("DELETE FROM app WHERE id = ?", id);
        audit.log("APP_DELETED", code, null);
    }

    public Map<String, Object> app(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, environment, description, slow_ms AS "slowMs", mask::text AS mask, route_rules::text AS "routeRules", created_at AS "createdAt"
                  FROM app WHERE code = ?""", code);
        if (rows.isEmpty()) throw ApiException.notFound("App " + code);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("mask", read((String) m.get("mask")));
        m.put("routeRules", read((String) m.get("routeRules")));
        return m;
    }

    // ------------------------------------------------------------------ agents

    /** Registers an agent and returns its key, which is shown this once, with a ready-made agent.json. */
    @Transactional
    public Map<String, Object> registerAgent(String code, String name, String serverUrl) {
        long appId = appId(code);
        if (name == null || name.isBlank()) throw ApiException.bad("Give the agent a name, e.g. the server it runs on");
        String id = "ag_" + hex(8);
        String key = base64url(32);
        jdbc.update("INSERT INTO agent (id, app_id, name, key_hash) VALUES (?, ?, ?, ?)", id, appId, name.trim(), sha256(key));
        audit.log("AGENT_REGISTERED", code, id + " " + name);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("server", Map.of("url", serverUrl, "agentId", id, "agentKey", key));
        cfg.put("spoolDir", "./spool");
        cfg.put("accessLogs", List.of(Map.of("path", "/var/log/nginx/access.log", "format", "nginx")));
        cfg.put("appLogs", List.of());
        cfg.put("db", null);
        cfg.put("allowServerSqlChecks", false);
        cfg.put("checks", List.of());
        cfg.put("host", Map.of("disks", List.of("/"), "processes", List.of(), "ports", List.of()));
        cfg.put("localReceiver", Map.of("enabled", true, "port", 7071));
        return Map.of("agentId", id, "agentKey", key, "agentJson", cfg);
    }

    public void revokeAgent(String code, String agentId) {
        long appId = appId(code);
        if (jdbc.update("UPDATE agent SET revoked = TRUE WHERE id = ? AND app_id = ?", agentId, appId) == 0) throw ApiException.notFound("Agent " + agentId);
        audit.log("AGENT_REVOKED", code, agentId);
    }

    public List<Map<String, Object>> agents(long appId) {
        return jdbc.queryForList("""
                SELECT id, name, host, version, last_seen AS "lastSeen", last_seq AS "lastSeq", revoked, created_at AS "createdAt"
                  FROM agent WHERE app_id = ? ORDER BY created_at""", appId);
    }

    // ------------------------------------------------------------------ checks

    public record CheckIn(String name, String kind, Map<String, Object> config, Integer intervalSec, Boolean health, Boolean enabled) {}

    public Map<String, Object> saveCheck(String code, Long checkId, CheckIn in) {
        long appId = appId(code);
        if (in.name() == null || in.name().isBlank()) throw ApiException.bad("Name is required");
        String kind = in.kind() == null ? "" : in.kind().toUpperCase(Locale.ROOT);
        Map<String, Object> c = in.config() == null ? Map.of() : in.config();
        if (kind.equals("HTTP")) {
            String url = String.valueOf(c.getOrDefault("url", ""));
            if (!url.startsWith("http://") && !url.startsWith("https://")) throw ApiException.bad("HTTP check: url must start with http:// or https://");
        } else if (kind.equals("SQL")) {
            String sql = String.valueOf(c.getOrDefault("sql", "")).trim().toLowerCase(Locale.ROOT);
            if (!(sql.startsWith("select") || sql.startsWith("with"))) throw ApiException.bad("SQL check: only SELECT or WITH queries");
            if (sql.contains(";")) throw ApiException.bad("SQL check: one statement, without ';'");
        } else throw ApiException.bad("Kind must be HTTP or SQL");
        int interval = in.intervalSec() == null ? (kind.equals("SQL") ? 300 : 60) : Math.max(15, Math.min(in.intervalSec(), 86400));
        if (checkId == null) {
            jdbc.update("INSERT INTO check_def (app_id, name, kind, config, interval_sec, is_health, enabled) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)",
                    appId, in.name().trim(), kind, write(c), interval, Boolean.TRUE.equals(in.health()), !Boolean.FALSE.equals(in.enabled()));
        } else if (jdbc.update("UPDATE check_def SET name = ?, kind = ?, config = ?::jsonb, interval_sec = ?, is_health = ?, enabled = ? WHERE id = ? AND app_id = ?",
                in.name().trim(), kind, write(c), interval, Boolean.TRUE.equals(in.health()), !Boolean.FALSE.equals(in.enabled()), checkId, appId) == 0)
            throw ApiException.notFound("Check");
        audit.log(checkId == null ? "CHECK_CREATED" : "CHECK_UPDATED", code, in.name());
        return Map.of("saved", true);
    }

    public void deleteCheck(String code, long checkId) {
        long appId = appId(code);
        jdbc.update("DELETE FROM check_def WHERE id = ? AND app_id = ?", checkId, appId);
        audit.log("CHECK_DELETED", code, String.valueOf(checkId));
    }

    public List<Map<String, Object>> checks(long appId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, name, kind, config::text AS config, interval_sec AS "intervalSec", is_health AS health, enabled
                  FROM check_def WHERE app_id = ? ORDER BY is_health DESC, name""", appId)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("config", read((String) r.get("config")));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ users

    public record UserIn(String username, String displayName, String password, String role, List<String> apps, Boolean active) {}

    @Transactional
    public Map<String, Object> createUser(UserIn in) {
        if (in.username() == null || !in.username().matches("^[a-zA-Z0-9._-]{3,60}$")) throw ApiException.bad("Username: 3–60 letters, digits, . _ or -");
        if (in.password() == null || in.password().length() < 10) throw ApiException.bad("Password: at least 10 characters");
        String role = "ADMIN".equals(in.role()) ? "ADMIN" : "VIEWER";
        long id = users.create(in.username(), blankTo(in.displayName(), in.username()), encoder.encode(in.password()), role);
        grant(id, in.apps());
        audit.log("USER_CREATED", in.username(), role + " " + in.apps());
        return Map.of("id", id);
    }

    @Transactional
    public void updateUser(long id, UserIn in) {
        if (in.role() != null) jdbc.update("UPDATE app_user SET role = ? WHERE id = ?", "ADMIN".equals(in.role()) ? "ADMIN" : "VIEWER", id);
        if (in.displayName() != null) jdbc.update("UPDATE app_user SET display_name = ? WHERE id = ?", in.displayName(), id);
        if (in.active() != null) jdbc.update("UPDATE app_user SET active = ? WHERE id = ?", in.active(), id);
        if (in.password() != null) {
            if (in.password().length() < 10) throw ApiException.bad("Password: at least 10 characters");
            jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", encoder.encode(in.password()), id);
        }
        if (in.apps() != null) {
            jdbc.update("DELETE FROM app_access WHERE user_id = ?", id);
            grant(id, in.apps());
        }
        audit.log("USER_UPDATED", String.valueOf(id), in.apps() == null ? null : "apps " + in.apps());
    }

    private void grant(long userId, List<String> codes) {
        if (codes == null) return;
        for (String c : codes) jdbc.update("INSERT INTO app_access (user_id, app_id) SELECT ?, id FROM app WHERE code = ? ON CONFLICT DO NOTHING", userId, c);
    }

    // ------------------------------------------------------------------ helpers

    long appId(String code) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM app WHERE code = ?", Long.class, code);
        if (ids.isEmpty()) throw ApiException.notFound("App " + code);
        return ids.get(0);
    }

    private static void validatePatterns(List<String> mask, List<Map<String, String>> rules) {
        try {
            if (mask != null) for (String m : mask) Pattern.compile(m);
            if (rules != null) for (Map<String, String> r : rules) Pattern.compile(Objects.requireNonNull(r.get("match"), "match"));
        } catch (Exception e) {
            throw ApiException.bad("Not a valid pattern: " + e.getMessage());
        }
    }

    private static int slow(Integer ms) { return ms == null ? 2000 : Math.max(100, Math.min(ms, 120_000)); }
    private static String blankTo(String s, String d) { return s == null || s.isBlank() ? d : s.trim(); }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Object read(String s) {
        try { return s == null ? null : json.readValue(s, Object.class); } catch (Exception e) { return null; }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String base64url(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
