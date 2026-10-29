package com.fyntune.agent;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one access-log line into a request: method, path, status, response time and, when logged, a request id.
 *
 * <p>The built-in formats all start with the common log format, {@code host ident user [time] "METHOD path PROTO" status bytes},
 * followed by the response time somewhere after it (README shows the log_format lines to use):
 * <ul>
 *   <li>nginx: {@code $request_time} in seconds, e.g. 0.042. An optional {@code $request_id} (hex) is picked up.</li>
 *   <li>apache: {@code %D} in microseconds.</li>
 *   <li>tomcat: {@code %D}, which is microseconds from Tomcat 10.1 (Spring Boot 3); set timeUnit "ms" for older
 *       Tomcat, where it was milliseconds.</li>
 * </ul>
 * custom: a regex with named groups method, path, status and time, and optionally rid and ts.
 */
public final class AccessLogParser {
    public record Line(long atMs, String method, String path, int status, long ms, String requestId) {}

    private static final Pattern COMMON = Pattern.compile(
            "^\\S+ \\S+ \\S+ \\[([^\\]]+)\\] \"([A-Z]{3,10}) (\\S+)(?: [^\"]*)?\" (\\d{3}) \\S+(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)?$");
    private static final Pattern RID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");
    private static final DateTimeFormatter CLF = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private final String format;
    private final double toMs;
    private final Pattern custom;

    public AccessLogParser(String format, String timeUnit, String regex) {
        this.format = format == null ? "nginx" : format.toLowerCase(Locale.ROOT);
        String unit = timeUnit != null ? timeUnit : switch (this.format) {
            case "apache", "tomcat" -> "us";
            default -> "s";
        };
        this.toMs = switch (unit) {
            case "us" -> 0.001;
            case "ms" -> 1;
            default -> 1000;
        };
        this.custom = "custom".equals(this.format) && regex != null ? Pattern.compile(regex) : null;
    }

    /** The request in this line, or null if the line is not one (blank, a header, another format). */
    public Line parse(String line) {
        if (line == null || line.isBlank()) return null;
        return custom != null ? parseCustom(line) : parseCommon(line);
    }

    private Line parseCommon(String line) {
        Matcher m = COMMON.matcher(line);
        if (!m.matches()) return null;
        long at = time(m.group(1));
        String rest = m.group(5).trim();
        // after the status and size: optional "referer" "agent", then the time and maybe a request id
        rest = rest.replaceAll("\"[^\"]*\"", " ").trim();
        Long ms = null;
        String rid = null;
        for (String tok : rest.isEmpty() ? new String[0] : rest.split("\\s+")) {
            if (ms == null && NUMBER.matcher(tok).matches()) ms = Math.round(Double.parseDouble(tok) * toMs);
            else if (rid == null && RID.matcher(tok).matches() && !NUMBER.matcher(tok).matches()) rid = tok;
        }
        return new Line(at, m.group(2), m.group(3), Integer.parseInt(m.group(4)), ms == null ? 0 : ms, rid);
    }

    private Line parseCustom(String line) {
        Matcher m = custom.matcher(line);
        if (!m.find()) return null;
        try {
            long at = group(m, "ts") == null ? System.currentTimeMillis() : time(group(m, "ts"));
            return new Line(at, m.group("method"), m.group("path"), Integer.parseInt(m.group("status")),
                    Math.round(Double.parseDouble(m.group("time")) * toMs), group(m, "rid"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String group(Matcher m, String name) {
        try { return m.group(name); } catch (IllegalArgumentException e) { return null; }
    }

    private static long time(String clf) {
        try {
            return ZonedDateTime.parse(clf, CLF).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
