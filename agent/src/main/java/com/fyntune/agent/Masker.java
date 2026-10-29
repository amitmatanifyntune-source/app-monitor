package com.fyntune.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks personal data in any text before it leaves the server: emails, Indian mobile numbers, PAN, Aadhaar, card
 * numbers, bearer tokens and passwords in key=value form, plus custom patterns from agent.json and the server.
 */
public final class Masker {
    private record Rule(Pattern p, String with) {}

    private static final List<Rule> BUILT_IN = List.of(
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 ****"),
            new Rule(Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization)(\"?\\s*[:=]\\s*\"?)[^\\s\"&,;]+"), "$1$2****"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "****@****"),
            // card numbers: 13–19 digits, optionally grouped
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"), "[card/number]"),
            // Aadhaar: 12 digits, optionally in groups of 4
            new Rule(Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"), "[aadhaar]"),
            // PAN: ABCDE1234F
            new Rule(Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "[pan]"),
            // Indian mobile numbers, keeping the last 4 digits
            new Rule(Pattern.compile("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{5}(\\d{4})(?!\\d)"), "••••••$1"));

    private volatile List<Rule> custom = List.of();

    public void setCustom(List<String> local, List<String> remote) {
        List<Rule> out = new ArrayList<>();
        for (List<String> l : List.of(local == null ? List.<String>of() : local, remote == null ? List.<String>of() : remote))
            for (String s : l) {
                try { out.add(new Rule(Pattern.compile(s), "****")); } catch (Exception ignored) { /* skipped */ }
            }
        custom = out;
    }

    public String mask(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (Rule r : custom) out = r.p().matcher(out).replaceAll(r.with());
        for (Rule r : BUILT_IN) out = r.p().matcher(out).replaceAll(r.with());
        return out;
    }

    /** Masks and cuts to n characters. */
    public String mask(String s, int n) {
        String m = mask(s);
        return m == null || m.length() <= n ? m : m.substring(0, n);
    }
}
