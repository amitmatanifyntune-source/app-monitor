package com.fyntune.agent;

import java.io.File;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fyntune App Monitor agent.
 *
 * <pre>
 * java -jar fyntune-agent.jar agent.json          run
 * java -jar fyntune-agent.jar agent.json --test   check the settings once and exit: server, logs, database, checks
 * </pre>
 *
 * Every second it reads new access-log and app-log lines; every few seconds it starts the checks that are due; once a
 * minute it sends a batch (finished minutes, events, check results, a database snapshot and host stats) through the
 * spool; every five minutes it fetches its settings from the server.
 */
public final class Main {
    public static final String VERSION = Optional.ofNullable(Main.class.getPackage().getImplementationVersion()).orElse("0.1.0-dev");
    /**
     * The start of a new log entry: a date or time (Spring, Laravel's "[2026-10-26 …]", Apache's "[26/Oct/2026 …]") or a
     * level word. Lines after an error line up to the next such line are its detail: the exception and stack trace.
     */
    static final Pattern NEW_ENTRY = Pattern.compile(
            "^(\\[?\\d{4}-\\d{2}-\\d{2}|\\[?\\d{2}/\\w{3}/\\d{4}|\\[?\\w{3} \\w{3} \\d{1,2} |\\d{2}:\\d{2}:\\d{2}|\\[?(TRACE|DEBUG|INFO|NOTICE|WARN|WARNING|ERROR|FATAL|SEVERE|CRITICAL)\\b)");
    static final int MAX_LOG_EVENTS_PER_MINUTE = 60;
    /** A request id written in a log line (ref=…, request_id=…, "requestId":"…"), to link the error to its request. */
    static final Pattern LOG_REQUEST_ID = Pattern.compile("(?i)\\b(?:ref|rid|request[_-]?id|trace[_-]?id|x-request-id)\"?\\s*[=:]\\s*\"?([0-9a-f][0-9a-f-]{7,63})\\b");

    private final AgentConfig cfg;
    private final State state;
    private final Masker masker = new Masker();
    private final RouteNormalizer routes = new RouteNormalizer();
    private final Aggregator agg = new Aggregator(masker);
    private final HttpClient http;
    private final Transport transport;
    private final Checks checks;
    private final DbProbe db;
    private final HostStats host;
    private final ServerSpec spec;
    private long specSentAt;
    private final List<Tail> accessLogs = new ArrayList<>();
    private final List<AppLogTail> appLogs = new ArrayList<>();
    private final String hostname;
    private LocalReceiver receiver;

    private record Tail(FileTailer tailer, AccessLogParser parser) {}

    private static final class AppLogTail {
        final FileTailer tailer;
        final Pattern error;
        Map<String, Object> last;   // the last error event, for its stack-trace lines
        long minute;
        int sentThisMinute, skippedThisMinute;
        AppLogTail(FileTailer tailer, Pattern error) { this.tailer = tailer; this.error = error; }
    }

    Main(AgentConfig cfg) throws Exception {
        this.cfg = cfg;
        this.state = new State(Path.of(cfg.spool()));
        this.http = Transport.client(cfg.tls());
        this.transport = new Transport(cfg, http);
        this.db = DbProbe.of(cfg.db(), masker);
        this.checks = new Checks(http, cfg.db(), masker);
        this.host = new HostStats(cfg.host());
        this.spec = new ServerSpec(cfg.host());
        String h;
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = "unknown"; }
        this.hostname = h;
        if (cfg.accessLogs() != null) for (AgentConfig.AccessLog a : cfg.accessLogs())
            accessLogs.add(new Tail(new FileTailer(Path.of(a.path()), state), new AccessLogParser(a.format(), a.timeUnit(), a.regex())));
        if (cfg.appLogs() != null) for (AgentConfig.AppLog a : cfg.appLogs())
            appLogs.add(new AppLogTail(new FileTailer(Path.of(a.path()), state),
                    Pattern.compile(a.errorPattern() == null ? "(?i)\\b(ERROR|CRITICAL|FATAL|EMERGENCY|ALERT|SEVERE)\\b|Exception\\b" : a.errorPattern())));
        apply(null);
    }

    /** Local settings, with the server's on top where the server may set them. */
    private void apply(RemoteConfig rc) {
        int slow = cfg.slowMs() != null ? cfg.slowMs() : rc != null && rc.slowMs() != null ? rc.slowMs() : 2000;
        agg.setSlowMs(slow);
        masker.setCustom(cfg.mask(), rc == null ? null : rc.mask());
        routes.setRules(cfg.routeRules(), rc == null ? null : rc.routeRules());
        checks.setChecks(cfg.checks(), rc == null ? null : rc.checks(), cfg.allowServerSqlChecks());
    }

    void run() throws Exception {
        Log.info("Fyntune agent " + VERSION + " for " + cfg.server().agentId() + " → " + cfg.server().url());
        for (Tail t : accessLogs) Log.info("Access log: " + t.tailer().path() + (Files.isReadable(t.tailer().path()) ? "" : " (not readable yet)"));
        for (AppLogTail t : appLogs) Log.info("App log: " + t.tailer.path() + (Files.isReadable(t.tailer.path()) ? "" : " (not readable yet)"));
        if (db != null) Log.info("Database probe: " + db.engine());
        RemoteConfig rc = transport.pullConfig();
        if (rc != null) { apply(rc); Log.info("Settings from the server: " + checks.current().size() + " check(s), slow above " + agg.slowMs() + " ms"); }

        var lr = cfg.localReceiver();
        if (lr == null || !Boolean.FALSE.equals(lr.enabled())) {
            try {
                receiver = new LocalReceiver(lr == null || lr.port() == null ? 7071 : lr.port(), lr == null ? null : lr.token(), agg, routes);
                receiver.start();
            } catch (Exception e) {
                Log.warn("Local receiver not started: " + e.getMessage());
            }
        }

        ScheduledExecutorService ex = Executors.newScheduledThreadPool(3, r -> { Thread t = new Thread(r, "agent"); t.setDaemon(false); return t; });
        ex.scheduleWithFixedDelay(guard("read logs", this::readLogs), 0, 1, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(guard("checks", checks::tick), 2, 5, TimeUnit.SECONDS);
        // just after each minute ends, so the finished minute goes in this batch
        long untilNextMinute = 60_000 - System.currentTimeMillis() % 60_000 + 2_000;
        ex.scheduleAtFixedRate(guard("send", this::sendBatch), untilNextMinute, 60_000, TimeUnit.MILLISECONDS);
        ex.scheduleWithFixedDelay(guard("retry", () -> { if (transport.pendingCount() > 0) transport.flush(); }), 15, 15, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(guard("settings", () -> { RemoteConfig r = transport.pullConfig(); if (r != null) apply(r); }), 300, 300, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(guard("state", state::save), 10, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Stopping: sending what is collected so far");
            try { sendBatch(Long.MAX_VALUE); } catch (Exception ignored) { }
            state.save();
            if (receiver != null) receiver.stop();
            if (db != null) db.close();
            checks.shutdown();
        }));
        Log.info("Running. First batch in " + untilNextMinute / 1000 + " s.");
    }

    private Runnable guard(String what, Runnable r) {
        return () -> {
            try { r.run(); } catch (Throwable t) { Log.warn(what + " failed: " + t); }
        };
    }

    void readLogs() {
        for (Tail t : accessLogs) {
            try {
                t.tailer().poll(line -> {
                    AccessLogParser.Line l = t.parser().parse(line);
                    if (l != null) agg.request(l.atMs(), l.method(), routes.normalize(l.path()), l.status(), l.ms(), l.requestId(), null, "access-log");
                });
            } catch (Exception e) {
                Log.debug("Access log " + t.tailer().path() + ": " + e.getMessage());
            }
        }
        for (AppLogTail t : appLogs) {
            // an error's stack-trace lines are added only while this lock is held, so a batch never takes one half-built;
            // a stack trace still being written when the poll ends is cut there
            synchronized (pendingLogEvents) {
                try {
                    t.tailer.poll(line -> appLogLine(t, line));
                } catch (Exception e) {
                    Log.debug("App log " + t.tailer.path() + ": " + e.getMessage());
                } finally {
                    t.last = null;
                }
            }
        }
    }

    private void appLogLine(AppLogTail t, String line) {
        // Spring writes a blank line between the error line and its exception
        if (t.last != null && line.isBlank()) return;
        if (t.last != null && !NEW_ENTRY.matcher(line).find()) {
            String d = (String) t.last.getOrDefault("detail", "");
            if (d.length() < 4000) t.last.put("detail", d.isEmpty() ? line : d + "\n" + line);
            return;
        }
        t.last = null;
        if (!t.error.matcher(line).find()) return;
        long minute = System.currentTimeMillis() / 60000;
        if (minute != t.minute) {
            if (t.skippedThisMinute > 0) agg.event(new LinkedHashMap<>(Map.of("at", System.currentTimeMillis(), "type", "LOG_ERROR",
                    "source", "log:" + t.tailer.path().getFileName(), "message", t.skippedThisMinute + " more error lines in the last minute (not kept)")));
            t.minute = minute;
            t.sentThisMinute = 0;
            t.skippedThisMinute = 0;
        }
        if (t.sentThisMinute >= MAX_LOG_EVENTS_PER_MINUTE) { t.skippedThisMinute++; return; }
        t.sentThisMinute++;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("at", System.currentTimeMillis());
        ev.put("type", "LOG_ERROR");
        ev.put("source", "log:" + t.tailer.path().getFileName());
        ev.put("message", line.length() > 600 ? line.substring(0, 600) : line);
        var rid = LOG_REQUEST_ID.matcher(line);
        if (rid.find()) ev.put("requestId", rid.group(1));
        ev.put("detail", "");
        t.last = ev;
        // masked when the batch is built (detail lines are still being added)
        pendingLogEvents.add(ev);
    }

    private final List<Map<String, Object>> pendingLogEvents = Collections.synchronizedList(new ArrayList<>());

    void sendBatch() { sendBatch(System.currentTimeMillis() / 60000); }

    synchronized void sendBatch(long beforeMinute) {
        synchronized (pendingLogEvents) {
            for (Map<String, Object> e : pendingLogEvents) {
                if ("".equals(e.get("detail"))) e.remove("detail");
                agg.event(e);
            }
            pendingLogEvents.clear();
        }
        Aggregator.Drained d = agg.drain(beforeMinute);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("v", 1);
        long seq = state.nextSeq();
        b.put("seq", seq);
        b.put("agentVersion", VERSION);
        b.put("host", hostname);
        b.put("sentAt", System.currentTimeMillis());
        b.put("minutes", d.minutes());
        b.put("endpoints", d.endpoints());
        b.put("events", d.events());
        b.put("checks", checks.drain());
        if (db != null) {
            DbProbe.Snapshot s = db.snapshot();
            b.put("db", Map.of("at", System.currentTimeMillis(), "engine", s.engine(), "version", s.version() == null ? "" : s.version(), "snapshot", s.data()));
        }
        b.put("hostStats", host.sample());
        // what the server is changes rarely: send it with the first batch, then hourly
        if (System.currentTimeMillis() - specSentAt >= 3_600_000) {
            b.put("spec", spec.collect());
            specSentAt = System.currentTimeMillis();
        }
        try {
            transport.enqueue(seq, b);
        } catch (Exception e) {
            Log.warn("Could not write batch " + seq + " to the spool: " + e.getMessage());
            return;
        }
        int sent = transport.flush();
        long reqs = d.minutes().stream().mapToLong(m -> ((Number) m.get("requests")).longValue()).sum();
        Log.debug("Batch " + seq + ": " + reqs + " requests, " + d.events().size() + " events; sent " + sent + ", waiting " + transport.pendingCount());
    }

    // ================================================================== --test

    int test() {
        int problems = 0;
        System.out.println("Fyntune agent " + VERSION + " — checking " + cfg.server().url());
        RemoteConfig rc = transport.pullConfig();
        if (rc == null) { problems++; System.out.println("  ✗ Server: cannot fetch settings (check server.url, agentId, agentKey and the network)"); }
        else { apply(rc); System.out.println("  ✓ Server: reachable, agent accepted; " + (rc.checks() == null ? 0 : rc.checks().size()) + " check(s) defined there"); }
        for (Tail t : accessLogs) {
            Path p = t.tailer().path();
            if (!Files.isReadable(p)) { problems++; System.out.println("  ✗ Access log " + p + ": not readable by this user"); continue; }
            try {
                List<String> lines = tail(p, 20);
                long parsed = lines.stream().map(t.parser()::parse).filter(Objects::nonNull).count();
                Optional<AccessLogParser.Line> sample = lines.stream().map(t.parser()::parse).filter(Objects::nonNull).reduce((a, b) -> b);
                if (lines.isEmpty()) System.out.println("  ✓ Access log " + p + ": readable (empty so far)");
                else if (parsed == 0) { problems++; System.out.println("  ✗ Access log " + p + ": none of the last " + lines.size() + " lines match its format; last line: " + masker.mask(lines.get(lines.size() - 1), 200)); }
                else System.out.println("  ✓ Access log " + p + ": " + parsed + "/" + lines.size() + " recent lines read; e.g. " + sample.map(l ->
                        l.method() + " " + routes.normalize(l.path()) + " → " + l.status() + " in " + l.ms() + " ms" + (l.ms() == 0 ? " (no response time in the log? see README)" : "")).orElse(""));
            } catch (Exception e) {
                problems++;
                System.out.println("  ✗ Access log " + p + ": " + e.getMessage());
            }
        }
        for (AppLogTail t : appLogs)
            System.out.println((Files.isReadable(t.tailer.path()) ? "  ✓ App log " : "  ✗ App log ") + t.tailer.path() + (Files.isReadable(t.tailer.path()) ? ": readable" : ": not readable by this user"));
        if (db != null) {
            DbProbe.Snapshot s = db.snapshot();
            boolean up = Boolean.TRUE.equals(s.data().get("up"));
            if (!up) problems++;
            System.out.println((up ? "  ✓ Database " : "  ✗ Database ") + s.engine() + " " + (s.version() == null ? "" : s.version()) + ": " + s.data().get("summary"));
            if (up && s.data().get("statements") instanceof Map<?, ?> st && !Boolean.TRUE.equals(st.get("available")))
                System.out.println("    note: " + st.get("note"));
            db.close();
        }
        for (Map<String, Object> c : checks.current()) {
            Map<String, Object> r = checks.run(c);
            if (!Boolean.TRUE.equals(r.get("ok"))) problems++;
            System.out.println((Boolean.TRUE.equals(r.get("ok")) ? "  ✓ Check " : "  ✗ Check ") + r.get("name") + ": " + r.get("message"));
        }
        Map<String, Object> h = host.sample();
        Map<String, Object> sp = spec.collect();
        System.out.println("  ✓ Server: " + sp.getOrDefault("hostname", "?") + ", " + sp.getOrDefault("os", "?") + ", " + sp.getOrDefault("cpuModel", "CPU unknown")
                + (sp.get("virtualization") != null ? ", " + sp.get("virtualization") : ""));
        System.out.println("  ✓ Host: " + h.get("os") + ", " + h.get("cores") + " cores, memory " + h.get("memUsedMb") + "/" + h.get("memTotalMb") + " MB");
        if (h.get("processes") instanceof List<?> ps) for (Object o : ps) if (o instanceof Map<?, ?> p)
            System.out.println((Boolean.TRUE.equals(p.get("up")) ? "  ✓ Process " : "  ✗ Process ") + p.get("name") + (Boolean.TRUE.equals(p.get("up")) ? ": running" : ": not found"));
        System.out.println(problems == 0 ? "All good." : problems + " problem(s) found.");
        checks.shutdown();
        return problems == 0 ? 0 : 1;
    }

    private static List<String> tail(Path p, int n) throws Exception {
        long size = Files.size(p);
        try (var f = new java.io.RandomAccessFile(p.toFile(), "r")) {
            long from = Math.max(0, size - 64 * 1024);
            f.seek(from);
            byte[] b = new byte[(int) (size - from)];
            f.readFully(b);
            List<String> lines = new ArrayList<>(Arrays.asList(new String(b, java.nio.charset.StandardCharsets.UTF_8).split("\n")));
            if (from > 0 && !lines.isEmpty()) lines.remove(0);
            lines.removeIf(String::isBlank);
            return lines.subList(Math.max(0, lines.size() - n), lines.size());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.out.println("Usage: java -jar fyntune-agent.jar agent.json [--test]");
            System.exit(2);
        }
        AgentConfig cfg;
        try {
            cfg = AgentConfig.load(new File(args[0]));
        } catch (Exception e) {
            System.out.println("Cannot read " + args[0] + ": " + e.getMessage());
            System.exit(2);
            return;
        }
        if (Arrays.asList(args).contains("--debug")) Log.debug = true;
        Main m = new Main(cfg);
        if (Arrays.asList(args).contains("--test")) System.exit(m.test());
        m.run();
    }
}
