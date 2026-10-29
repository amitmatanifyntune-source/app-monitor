package com.fyntune.agent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts requests per minute, for the whole app and per endpoint, and keeps the events worth looking at one by one:
 * server errors, refusals and slow requests. Finished minutes are handed to the next batch by {@link #drain}.
 */
public final class Aggregator {
    /** Events kept between batches; past this they are only counted. */
    static final int MAX_EVENTS = 1000;

    private static final class Bucket {
        final Hist h = new Hist();
        long errors, refused, slow;
    }

    private final Map<Long, Bucket> minutes = new TreeMap<>();
    private final Map<Long, Map<String, Bucket>> endpoints = new TreeMap<>();
    private final Queue<Map<String, Object>> events = new ConcurrentLinkedQueue<>();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger droppedEvents = new AtomicInteger();
    private final Masker masker;
    private volatile int slowMs = 2000;

    public Aggregator(Masker masker) {
        this.masker = masker;
    }

    public void setSlowMs(int ms) { this.slowMs = Math.max(100, ms); }
    public int slowMs() { return slowMs; }

    /** One request, from an access log or an in-app library. pattern is already normalised. */
    public void request(long atMs, String method, String pattern, int status, long ms, String requestId, String actor, String source) {
        long minute = atMs / 60000;
        String key = method + " " + pattern;
        String type = status >= 500 ? "ERROR" : status >= 400 && status != 401 && status != 403 && status != 404 ? "REFUSED" : ms >= slowMs ? "SLOW" : null;
        synchronized (this) {
            Bucket b = minutes.computeIfAbsent(minute, k -> new Bucket());
            Bucket e = endpoints.computeIfAbsent(minute, k -> new HashMap<>()).computeIfAbsent(key, k -> new Bucket());
            for (Bucket x : new Bucket[]{b, e}) {
                x.h.add(ms);
                if (status >= 500) x.errors++;
                else if ("REFUSED".equals(type)) x.refused++;
                if (ms >= slowMs) x.slow++;
            }
        }
        if (type != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("at", atMs);
            ev.put("type", type);
            ev.put("source", source);
            ev.put("requestId", requestId);
            ev.put("method", method);
            ev.put("path", pattern);
            ev.put("status", status);
            ev.put("ms", ms);
            ev.put("actor", masker.mask(actor, 120));
            ev.put("message", "HTTP " + status + (ms >= slowMs ? " in " + ms + " ms" : ""));
            event(ev);
        }
    }

    /** Any other event (log error, app exception, …). Text fields are masked here. */
    public void event(Map<String, Object> ev) {
        if (eventCount.get() >= MAX_EVENTS) { droppedEvents.incrementAndGet(); return; }
        for (String f : List.of("message", "detail", "actor", "path")) {
            Object v = ev.get(f);
            if (v instanceof String s) ev.put(f, masker.mask(s, "detail".equals(f) ? 8000 : 600));
        }
        events.add(ev);
        eventCount.incrementAndGet();
    }

    public record Drained(List<Map<String, Object>> minutes, List<Map<String, Object>> endpoints, List<Map<String, Object>> events) {}

    /** Minutes before {@code beforeMinute} (finished), and every event collected so far. */
    public Drained drain(long beforeMinute) {
        List<Map<String, Object>> ms = new ArrayList<>(), eps = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Map.Entry<Long, Bucket>> it = minutes.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getKey() >= beforeMinute) break;
                ms.add(row(e.getKey(), null, null, e.getValue()));
                it.remove();
            }
            for (Iterator<Map.Entry<Long, Map<String, Bucket>>> it = endpoints.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getKey() >= beforeMinute) break;
                e.getValue().forEach((k, b) -> {
                    int sp = k.indexOf(' ');
                    eps.add(row(e.getKey(), k.substring(0, sp), k.substring(sp + 1), b));
                });
                it.remove();
            }
        }
        List<Map<String, Object>> evs = new ArrayList<>();
        Map<String, Object> ev;
        while ((ev = events.poll()) != null) { evs.add(ev); eventCount.decrementAndGet(); }
        int dropped = droppedEvents.getAndSet(0);
        if (dropped > 0) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("at", System.currentTimeMillis());
            d.put("type", "APP");
            d.put("source", "agent");
            d.put("message", dropped + " further events in this minute were counted but not kept (limit " + MAX_EVENTS + ")");
            evs.add(d);
        }
        return new Drained(ms, eps, evs);
    }

    private static Map<String, Object> row(long minute, String method, String pattern, Bucket b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minute", minute);
        if (method != null) { m.put("method", method); m.put("pattern", pattern); }
        m.put("requests", b.h.count);
        m.put("errors", b.errors);
        m.put("refused", b.refused);
        m.put("slow", b.slow);
        m.put("totalMs", b.h.total);
        m.put("maxMs", b.h.max);
        m.put("hist", b.h.counts());
        return m;
    }
}
