package com.fyntune.monitor.ingest;

import com.fyntune.monitor.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Checks an agent's X-Agent-Id and X-Agent-Key against the stored hash. */
@Service
public class AgentAuth {
    public record Agent(String id, long appId) {}

    private final JdbcTemplate jdbc;

    public AgentAuth(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Agent check(String agentId, String key) {
        if (agentId == null || key == null || agentId.length() > 24 || key.length() > 100) throw unauthorized();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT app_id, key_hash, revoked FROM agent WHERE id = ?", agentId);
        // hash even when the agent is unknown, so a wrong id and a wrong key take the same time
        byte[] given = sha256(key).getBytes(StandardCharsets.US_ASCII);
        byte[] stored = rows.isEmpty() ? new byte[64] : ((String) rows.get(0).get("key_hash")).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, stored) || rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("revoked"))) throw unauthorized();
        return new Agent(agentId, ((Number) rows.get(0).get("app_id")).longValue());
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Unknown or revoked agent, or wrong key");
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
