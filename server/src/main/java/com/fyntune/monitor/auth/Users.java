package com.fyntune.monitor.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class Users {
    private final JdbcTemplate jdbc;

    public Users(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuthUser> active(String username) {
        return jdbc.query("SELECT id, username, display_name, role FROM app_user WHERE username = ? AND active",
                (rs, i) -> new AuthUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("role")), username)
                .stream().findFirst();
    }

    public Optional<String> passwordHash(String username) {
        return jdbc.queryForList("SELECT password_hash FROM app_user WHERE username = ? AND active", String.class, username).stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
        return n == null ? 0 : n;
    }

    public long create(String username, String displayName, String hash, String role) {
        Long id = jdbc.queryForObject("INSERT INTO app_user (username, display_name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, username, displayName, hash, role);
        return id == null ? 0 : id;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.display_name AS "displayName", u.role, u.active, u.created_at AS "createdAt",
                       coalesce((SELECT json_agg(a.code ORDER BY a.code)::text FROM app_access x JOIN app a ON a.id = x.app_id WHERE x.user_id = u.id), '[]') AS apps
                  FROM app_user u ORDER BY u.username""");
    }
}
