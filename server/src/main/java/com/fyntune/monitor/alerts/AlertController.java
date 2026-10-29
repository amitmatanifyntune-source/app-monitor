package com.fyntune.monitor.alerts;

import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Where an app's alerts go, a test message, and pausing alerts during planned work. Administrators only (/api/admin/**). */
@RestController
@RequestMapping("/api/admin/apps/{code}/alerts")
public class AlertController {
    // the server posts to this address, so only Slack's own webhook host is accepted
    private static final Pattern SLACK = Pattern.compile("^https://hooks\\.slack\\.com/(services|workflows|triggers)/[A-Za-z0-9/_-]{10,300}$");
    private static final Pattern PHONE = Pattern.compile("^[1-9][0-9]{7,14}$");
    private final JdbcTemplate jdbc;
    private final Alerter alerter;
    private final AlertSender sender;
    private final Audit audit;

    public AlertController(JdbcTemplate jdbc, Alerter alerter, AlertSender sender, Audit audit) {
        this.jdbc = jdbc;
        this.alerter = alerter;
        this.sender = sender;
        this.audit = audit;
    }

    public record ChannelIn(String kind, String target, String label, Boolean includeDegraded, Boolean enabled) {}
    public record MuteIn(Integer minutes) {}

    @GetMapping
    public Map<String, Object> list(@PathVariable String code) {
        long app = appId(code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("whatsappReady", sender.whatsappReady());
        m.put("mutedUntil", jdbc.queryForObject("SELECT CASE WHEN muted_until > now() THEN muted_until END FROM app WHERE id = ?", java.time.OffsetDateTime.class, app));
        m.put("channels", jdbc.query("""
                SELECT id, kind, target, label, include_degraded, enabled FROM alert_channel WHERE app_id = ? ORDER BY id""", (rs, i) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", rs.getLong("id"));
            c.put("kind", rs.getString("kind"));
            c.put("target", shown(rs.getString("kind"), rs.getString("target")));
            c.put("label", rs.getString("label"));
            c.put("includeDegraded", rs.getBoolean("include_degraded"));
            c.put("enabled", rs.getBoolean("enabled"));
            return c;
        }, app));
        m.put("recent", jdbc.queryForList("""
                SELECT l.at, l.kind, l.status, l.ok, l.message, l.error, c.kind AS "channelKind", c.label AS "channelLabel"
                  FROM alert_log l LEFT JOIN alert_channel c ON c.id = l.channel_id WHERE l.app_id = ? ORDER BY l.at DESC LIMIT 20""", app));
        return m;
    }

    @PostMapping
    public Map<String, Object> add(@PathVariable String code, @RequestBody ChannelIn in) {
        long app = appId(code);
        String kind = in.kind() == null ? "" : in.kind().trim().toUpperCase();
        String target = target(kind, in.target());
        Long id = jdbc.queryForObject("""
                INSERT INTO alert_channel (app_id, kind, target, label, include_degraded, enabled) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                app, kind, target, blank(in.label()), in.includeDegraded() == null || in.includeDegraded(), in.enabled() == null || in.enabled());
        audit.log("ALERT_CHANNEL_ADDED", code, kind + " " + shown(kind, target));
        return Map.of("id", id);
    }

    @PatchMapping("/{id}")
    public void update(@PathVariable String code, @PathVariable long id, @RequestBody ChannelIn in) {
        int n = jdbc.update("""
                UPDATE alert_channel SET label = coalesce(?, label), include_degraded = coalesce(?, include_degraded), enabled = coalesce(?, enabled)
                 WHERE id = ? AND app_id = ?""", in.label() == null ? null : in.label().trim(), in.includeDegraded(), in.enabled(), id, appId(code));
        if (n == 0) throw ApiException.notFound("Alert channel");
        audit.log("ALERT_CHANNEL_UPDATED", code, "channel " + id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String code, @PathVariable long id) {
        if (jdbc.update("DELETE FROM alert_channel WHERE id = ? AND app_id = ?", id, appId(code)) == 0) throw ApiException.notFound("Alert channel");
        audit.log("ALERT_CHANNEL_DELETED", code, "channel " + id);
    }

    /** Sends a test message now; a failure comes back with the service's own answer so it can be fixed. */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String code, @PathVariable long id) {
        long app = appId(code);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT kind, target FROM alert_channel WHERE id = ? AND app_id = ?", id, app);
        if (rows.isEmpty()) throw ApiException.notFound("Alert channel");
        Map<String, Object> a = jdbc.queryForMap("SELECT code, name, environment FROM app WHERE id = ?", app);
        String error = alerter.deliver(app, id, (String) rows.get(0).get("kind"), (String) rows.get(0).get("target"),
                alerter.alert("TEST", a, "UP", null, List.of(), 0));
        if (error != null) throw ApiException.bad("Not delivered. " + error);
        return Map.of("sent", true);
    }

    /** minutes: how long to hold alerts, up to 7 days; 0 resumes them. */
    @PutMapping("/mute")
    public void mute(@PathVariable String code, @RequestBody MuteIn in) {
        int minutes = in.minutes() == null ? 0 : in.minutes();
        if (minutes < 0 || minutes > 7 * 1440) throw ApiException.bad("Pause for 0 minutes to 7 days");
        jdbc.update("UPDATE app SET muted_until = CASE WHEN ? = 0 THEN NULL ELSE now() + make_interval(mins => ?) END WHERE id = ?", minutes, minutes, appId(code));
        audit.log(minutes == 0 ? "ALERTS_RESUMED" : "ALERTS_PAUSED", code, minutes == 0 ? null : minutes + " minutes");
    }

    private String target(String kind, String raw) {
        String t = raw == null ? "" : raw.trim();
        switch (kind) {
            case "SLACK" -> {
                if (!SLACK.matcher(t).matches()) throw ApiException.bad("Paste the Slack incoming-webhook URL; it starts with https://hooks.slack.com/");
                return t;
            }
            case "WHATSAPP" -> {
                if (!sender.whatsappReady()) throw ApiException.bad("WhatsApp is not set up on this server yet. See README: Alerts.");
                String digits = t.replaceAll("[\\s()+-]", "");
                // ten digits from 6 to 9 with no "+" is an Indian mobile without its country code
                if (!t.startsWith("+") && digits.matches("[6-9][0-9]{9}"))
                    throw ApiException.bad("Include the country code, e.g. +91 98765 43210");
                if (!PHONE.matcher(digits).matches()) throw ApiException.bad("A mobile number with its country code, e.g. +91 98765 43210");
                return digits;
            }
            default -> throw ApiException.bad("Channel is SLACK or WHATSAPP");
        }
    }

    /** A webhook URL is a password: show only where it points and its last characters. */
    static String shown(String kind, String target) {
        if ("SLACK".equals(kind)) return "hooks.slack.com/…" + target.substring(Math.max(0, target.length() - 4));
        return "+" + target;
    }

    private long appId(String code) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM app WHERE code = ?", Long.class, code);
        if (ids.isEmpty()) throw ApiException.notFound("App " + code);
        return ids.get(0);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
