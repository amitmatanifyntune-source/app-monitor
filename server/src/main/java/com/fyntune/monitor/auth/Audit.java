package com.fyntune.monitor.auth;

import com.fyntune.monitor.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Who signed in, looked at which app and changed what. Kept with the monitoring data, never sent anywhere. */
@Service
public class Audit {
    private final JdbcTemplate jdbc;

    public Audit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String username, String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_log (username, action, target, detail) VALUES (?, ?, ?, ?)",
                username, action, target, detail == null ? null : detail.length() > 600 ? detail.substring(0, 600) : detail);
    }

    public void log(String action, String target, String detail) {
        String who;
        try { who = CurrentUser.get().username(); } catch (ApiException e) { who = null; }
        log(who, action, target, detail);
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT at, username, action, target, detail FROM audit_log ORDER BY at DESC LIMIT ?", Math.min(Math.max(limit, 1), 500));
    }
}
