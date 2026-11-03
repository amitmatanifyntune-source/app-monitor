package com.fyntune.agent;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What the agent itself costs the server it watches: CPU (share of the whole machine, like the host figure), resident
 * memory, Java heap, threads, spool size on disk, and how much OpenTelemetry data it handled since the last batch.
 * Sent with every host sample, so the Servers tab can show the agent's overhead next to the app.
 */
public final class AgentSelf {
    private final Path spool;
    private final long startedAt = System.currentTimeMillis();

    public AgentSelf(Path spool) {
        this.spool = spool;
    }

    public Map<String, Object> sample(Map<String, Object> otel, boolean otlpListening) {
        Map<String, Object> m = new LinkedHashMap<>();
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double cpu = sun.getProcessCpuLoad();
            if (cpu >= 0) m.put("cpu", Math.round(cpu * 10000) / 10000.0);
        }
        long rss = rssKb();
        Runtime rt = Runtime.getRuntime();
        m.put("rssMb", rss > 0 ? rss / 1024 : (rt.totalMemory() + nonHeapBytes()) / 1048576);
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        m.put("heapMaxMb", rt.maxMemory() / 1048576);
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000);
        m.put("spoolKb", dirKb(spool));
        m.put("otlp", otlpListening);
        if (otel != null) m.putAll(otel);
        return m;
    }

    /** Resident memory: Linux VmRSS from /proc/self/status; macOS and other Unix `ps -o rss=`; 0 if neither works. */
    static long rssKb() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) { /* not Linux */ }
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && out.matches("\\d+")) return Long.parseLong(out);
        } catch (Exception ignored) { /* no ps */ }
        return 0;
    }

    private static long nonHeapBytes() {
        try { return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted(); } catch (Exception e) { return 0; }
    }

    static long dirKb(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum() / 1024;
        } catch (Exception e) {
            return 0;
        }
    }
}
