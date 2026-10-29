package com.fyntune.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a request path into a route pattern, so /api/orders/123 and /api/orders/456 count as one endpoint:
 * /api/orders/{id}. The query string is always dropped (it may carry personal data or tokens).
 */
public final class RouteNormalizer {
    private record Rule(Pattern match, String replace) {}

    // segments that are ids: numbers, UUIDs, long hex, reference numbers such as APP-123 or QTN-2026-14, and long tokens
    private static final Pattern NUM = Pattern.compile("^\\d+$");
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{12,}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z]{1,6}-[A-Za-z0-9]*\\d[A-Za-z0-9-]*$");
    // long and mixing letters with digits: a token or key, not a word like insurance-accounting-overview
    private static final Pattern TOKEN = Pattern.compile("^(?=.*\\d)(?=.*[A-Za-z])[A-Za-z0-9_\\-.=]{24,}$");
    private static final Pattern EMAILISH = Pattern.compile(".*@.*");
    private static final Pattern MOBILE = Pattern.compile("^\\+?\\d{10,13}$");

    private volatile List<Rule> rules = List.of();

    /** Rules from agent.json and the server: {"match": regex on the whole path, "replace": replacement}. Applied first. */
    public void setRules(List<Map<String, String>> local, List<Map<String, String>> remote) {
        List<Rule> out = new ArrayList<>();
        for (List<Map<String, String>> l : new List[]{local, remote}) {
            if (l == null) continue;
            for (Map<String, String> r : l) {
                try {
                    if (r.get("match") != null) out.add(new Rule(Pattern.compile(r.get("match")), r.getOrDefault("replace", "{x}")));
                } catch (Exception ignored) {
                    // a bad rule is skipped, not fatal
                }
            }
        }
        rules = out;
    }

    public String normalize(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        String p = rawPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        // absolute-form request targets (proxies): keep only the path
        if (p.startsWith("http://") || p.startsWith("https://")) {
            int s = p.indexOf('/', p.indexOf("//") + 2);
            p = s < 0 ? "/" : p.substring(s);
        }
        for (Rule r : rules) {
            var m = r.match().matcher(p);
            if (m.find()) { p = m.replaceAll(r.replace()); break; }
        }
        String[] parts = p.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (i > 0) out.append('/');
            if (s.isEmpty() || s.startsWith("{")) { out.append(s); continue; }
            if (NUM.matcher(s).matches() || UUID.matcher(s).matches() || HEX.matcher(s).matches() || MOBILE.matcher(s).matches()) out.append("{id}");
            else if (REF.matcher(s).matches()) out.append("{ref}");
            else if (EMAILISH.matcher(s).matches()) out.append("{email}");
            else if (TOKEN.matcher(s).matches()) out.append("{token}");
            else out.append(s);
        }
        String r = out.toString();
        if (r.length() > 300) r = r.substring(0, 300);
        return r.isEmpty() ? "/" : r;
    }
}
