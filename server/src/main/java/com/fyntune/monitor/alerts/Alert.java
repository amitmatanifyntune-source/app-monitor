package com.fyntune.monitor.alerts;

import java.util.List;

/**
 * One message about one app. CHANGE: it became Down or Degraded (or went from Down to Degraded). RECOVERED: it is Up
 * again. REMINDER: it is still Down. TEST: sent from Setup to prove a channel works.
 */
public record Alert(String kind, String app, String environment, String code, String status, String previous, List<String> reasons,
                    long minutes, String link) {

    public String headline() {
        String who = app + " (" + environment + ")";
        return switch (kind) {
            case "TEST" -> "Test alert for " + who + ": this channel is set up";
            case "REMINDER" -> who + " is still Down, for " + duration(minutes);
            case "RECOVERED" -> who + " is Up again, after " + duration(minutes) + " " + word(previous);
            default -> "DEGRADED".equals(status) && "DOWN".equals(previous) ? who + " is Degraded, no longer Down" : who + " is " + word(status);
        };
    }

    /** At most three reasons, each cut short; the full list is on screen. */
    public String details() {
        if (reasons == null || reasons.isEmpty()) return "";
        List<String> r = reasons.stream().limit(3).map(s -> s.length() > 160 ? s.substring(0, 157) + "…" : s).toList();
        return String.join("; ", r) + (reasons.size() > 3 ? "; and " + (reasons.size() - 3) + " more" : "");
    }

    static String word(String status) {
        return switch (status == null ? "" : status) {
            case "DOWN" -> "Down";
            case "DEGRADED" -> "Degraded";
            case "UP" -> "Up";
            default -> "No data";
        };
    }

    static String duration(long minutes) {
        if (minutes < 1) return "under a minute";
        if (minutes < 120) return minutes + " min";
        return minutes / 60 + " h " + minutes % 60 + " min";
    }
}
