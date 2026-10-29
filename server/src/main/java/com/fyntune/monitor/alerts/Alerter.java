package com.fyntune.monitor.alerts;

import com.fyntune.monitor.view.ViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Every 30 seconds, compares each app's status with the one last alerted for. A new status counts once it has held
 * for app.alerts.confirm-seconds; then its channels hear about it once. Down is repeated every app.alerts.repeat-minutes.
 * No data is never alerted. While an app is paused the state still moves on, so nothing stale is sent afterwards.
 */
@Component
public class Alerter {
    private static final Logger log = LoggerFactory.getLogger(Alerter.class);
    private final JdbcTemplate jdbc;
    private final ViewService view;
    private final AlertSender sender;
    private final int confirmSeconds, repeatMinutes;
    private final String publicUrl;

    public Alerter(JdbcTemplate jdbc, ViewService view, AlertSender sender, @Value("${app.alerts.confirm-seconds}") int confirmSeconds,
                   @Value("${app.alerts.repeat-minutes}") int repeatMinutes, @Value("${app.alerts.public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.view = view;
        this.sender = sender;
        this.confirmSeconds = confirmSeconds;
        this.repeatMinutes = repeatMinutes;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${app.alerts.every-ms}", initialDelayString = "${app.alerts.initial-delay-ms}")
    public void run() {
        for (Map<String, Object> app : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, a.environment, coalesce(a.muted_until > now(), false) AS muted,
                       s.status, s.since, s.pending_status, s.pending_since, s.last_sent_at
                  FROM app a LEFT JOIN alert_state s ON s.app_id = a.id""")) {
            try {
                evaluate(app);
            } catch (Exception e) {
                log.warn("Alerts for {} failed: {}", app.get("code"), e.getMessage());
            }
        }
    }

    void evaluate(Map<String, Object> app) {
        long id = ((Number) app.get("id")).longValue();
        boolean muted = (Boolean) app.get("muted");
        ViewService.Status st = view.status(id);
        String now = st.status();
        String prev = (String) app.get("status");
        if (prev == null) {   // first look at this app: start from Up, so an app that is already Down is alerted once
            jdbc.update("INSERT INTO alert_state (app_id, status, since) VALUES (?, 'UP', now()) ON CONFLICT DO NOTHING", id);
            prev = "UP";
        }
        if ("NO_DATA".equals(now)) return;

        if (now.equals(prev)) {
            if (app.get("pending_status") != null) jdbc.update("UPDATE alert_state SET pending_status = NULL, pending_since = NULL WHERE app_id = ?", id);
            Instant last = instant(app.get("last_sent_at"), instant(app.get("since"), Instant.now()));
            if ("DOWN".equals(now) && !muted && last.isBefore(Instant.now().minus(Duration.ofMinutes(repeatMinutes)))) {
                long minutes = Duration.between(instant(app.get("since"), Instant.now()), Instant.now()).toMinutes();
                if (notify(id, alert("REMINDER", app, now, prev, st.reasons(), minutes), true))
                    jdbc.update("UPDATE alert_state SET last_sent_at = now() WHERE app_id = ?", id);
            }
            return;
        }

        Instant pendingSince = instant(app.get("pending_since"), null);
        boolean held = now.equals(app.get("pending_status")) && pendingSince != null && pendingSince.isBefore(Instant.now().minusSeconds(confirmSeconds));
        if (confirmSeconds > 0 && !held) {
            if (!now.equals(app.get("pending_status")))
                jdbc.update("UPDATE alert_state SET pending_status = ?, pending_since = now() WHERE app_id = ?", now, id);
            return;
        }

        long minutes = Duration.between(instant(app.get("since"), Instant.now()), Instant.now()).toMinutes();
        jdbc.update("UPDATE alert_state SET status = ?, since = now(), pending_status = NULL, pending_since = NULL WHERE app_id = ?", now, id);
        if (muted) return;
        Alert a = alert("UP".equals(now) ? "RECOVERED" : "CHANGE", app, now, prev, st.reasons(), minutes);
        // Down, and anything that follows Down, goes to every channel; Degraded only to channels that asked for it
        if (notify(id, a, "DOWN".equals(now) || "DOWN".equals(prev)))
            jdbc.update("UPDATE alert_state SET last_sent_at = now() WHERE app_id = ?", id);
    }

    /** Sends to the app's enabled channels; true when at least one message went out. */
    private boolean notify(long appId, Alert a, boolean everyChannel) {
        boolean any = false;
        for (Map<String, Object> ch : jdbc.queryForList(
                "SELECT id, kind, target FROM alert_channel WHERE app_id = ? AND enabled AND (? OR include_degraded) ORDER BY id", appId, everyChannel))
            any |= deliver(appId, ((Number) ch.get("id")).longValue(), (String) ch.get("kind"), (String) ch.get("target"), a) == null;
        return any;
    }

    /** Sends one message and logs it; returns the error, or null when it was delivered. */
    String deliver(long appId, long channelId, String kind, String target, Alert a) {
        String error = null;
        try {
            sender.send(kind, target, a);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Alert to {} channel {} failed: {}", kind, channelId, error);
        }
        jdbc.update("INSERT INTO alert_log (app_id, channel_id, kind, status, ok, message, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
                appId, channelId, a.kind(), a.status(), error == null, cut(a.headline()), error == null ? null : cut(error));
        return error;
    }

    Alert alert(String kind, Map<String, Object> app, String status, String previous, List<String> reasons, long minutes) {
        return new Alert(kind, (String) app.get("name"), (String) app.get("environment"), (String) app.get("code"), status, previous, reasons, minutes,
                publicUrl.isEmpty() ? null : publicUrl + "/apps/" + app.get("code"));
    }

    private static Instant instant(Object o, Instant fallback) {
        if (o instanceof OffsetDateTime t) return t.toInstant();
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        return fallback;
    }

    private static String cut(String s) { return s.length() > 600 ? s.substring(0, 600) : s; }
}
