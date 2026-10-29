package com.fyntune.agent;

/** Response times in buckets (PROTOCOL.md). The same buckets as the server and Proton core's OpsRecorder. */
public final class Hist {
    public static final long[] BOUNDS = {10, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, Long.MAX_VALUE};

    final long[] n = new long[BOUNDS.length];
    long count, total, max;

    public void add(long ms) {
        for (int i = 0; i < BOUNDS.length; i++) if (ms <= BOUNDS[i]) { n[i]++; break; }
        count++;
        total += ms;
        max = Math.max(max, ms);
    }

    public long pct(double p) {
        if (count == 0) return 0;
        long want = (long) Math.ceil(count * p), seen = 0;
        for (int i = 0; i < n.length; i++) {
            seen += n[i];
            if (seen >= want) return i == n.length - 1 ? max : Math.min(BOUNDS[i], max);
        }
        return max;
    }

    public long[] counts() { return n.clone(); }
}
