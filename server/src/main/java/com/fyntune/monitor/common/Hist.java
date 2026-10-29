package com.fyntune.monitor.common;

/**
 * Response-time histograms as agents send them: counts per bucket, upper bounds in {@link #BOUNDS}. The same buckets as
 * Proton core's OpsRecorder, so percentiles can be added up across agents and minutes without losing accuracy.
 */
public final class Hist {
    public static final long[] BOUNDS = {10, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, Long.MAX_VALUE};

    private Hist() {}

    public static long[] empty() { return new long[BOUNDS.length]; }

    /** Adds b into a (a is changed). Missing or short arrays count as zeros. */
    public static long[] add(long[] a, long[] b) {
        if (b == null) return a;
        for (int i = 0; i < a.length && i < b.length; i++) a[i] += b[i];
        return a;
    }

    /** The p-th percentile (0..1), to the bucket's upper bound, and never above the largest value seen. */
    public static long pct(long[] h, long max, double p) {
        long count = 0;
        for (long n : h) count += n;
        if (count == 0) return 0;
        long want = (long) Math.ceil(count * p), seen = 0;
        for (int i = 0; i < h.length; i++) {
            seen += h[i];
            if (seen >= want) return i == h.length - 1 ? max : Math.min(BOUNDS[i], max);
        }
        return max;
    }

    public static long[] of(Object sqlArray) {
        try {
            if (sqlArray instanceof java.sql.Array a) sqlArray = a.getArray();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        if (sqlArray instanceof Long[] l) {
            long[] out = empty();
            for (int i = 0; i < out.length && i < l.length; i++) out[i] = l[i] == null ? 0 : l[i];
            return out;
        }
        return empty();
    }

    public static Long[] boxed(long[] h) {
        Long[] out = new Long[BOUNDS.length];
        for (int i = 0; i < out.length; i++) out[i] = h != null && i < h.length ? h[i] : 0L;
        return out;
    }
}
