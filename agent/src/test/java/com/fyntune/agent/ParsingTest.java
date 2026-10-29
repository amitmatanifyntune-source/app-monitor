package com.fyntune.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParsingTest {

    @Test
    void nginxLineWithRequestTimeAndId() {
        var p = new AccessLogParser("nginx", null, null);
        var l = p.parse("10.0.0.5 - - [26/Oct/2026:19:30:27 +0530] \"POST /api/orders/123/pay?card=4111 HTTP/1.1\" 502 157 \"-\" \"Mozilla/5.0 (X11)\" 0.734 9f2c1ab07e3d4c55");
        assertNotNull(l);
        assertEquals("POST", l.method());
        assertEquals("/api/orders/123/pay?card=4111", l.path());
        assertEquals(502, l.status());
        assertEquals(734, l.ms());
        assertEquals("9f2c1ab07e3d4c55", l.requestId());
        assertEquals(1793023227000L, l.atMs());
    }

    @Test
    void apacheMicrosecondsAndTomcatMilliseconds() {
        var a = new AccessLogParser("apache", null, null).parse("127.0.0.1 - frank [10/Oct/2026:13:55:36 -0700] \"GET /index.php HTTP/1.1\" 200 2326 \"-\" \"curl/8\" 45210");
        assertEquals(45, a.ms());
        // a real line from Proton core (Tomcat 10.1, pattern '%h %l %u %t "%r" %s %b %D %{X-Request-Id}o')
        var t = new AccessLogParser("tomcat", null, null).parse("0:0:0:0:0:0:0:1 - - [26/Oct/2026:19:44:57 +0530] \"POST /api/auth/login HTTP/1.1\" 401 96 162103 8dd85cbc");
        assertEquals(162, t.ms());
        assertEquals("/api/auth/login", t.path());
        assertEquals("8dd85cbc", t.requestId());
        assertNull(new AccessLogParser("tomcat", null, null).parse("::1 - - [26/Oct/2026:19:44:57 +0530] \"GET /api/public/health HTTP/1.1\" 200 84 1605 -").requestId());
        var old = new AccessLogParser("tomcat", "ms", null).parse("::1 - - [26/Oct/2026:19:40:01 +0530] \"GET /x HTTP/1.1\" 200 - 37");
        assertEquals(37, old.ms());
    }

    @Test
    void customRegex() {
        var p = new AccessLogParser("custom", "ms", "^(?<method>[A-Z]+) (?<path>\\S+) (?<status>\\d{3}) (?<time>\\d+)ms rid=(?<rid>\\w+)");
        var l = p.parse("GET /health 200 12ms rid=abc123");
        assertEquals(12, l.ms());
        assertEquals("abc123", l.requestId());
    }

    @Test
    void notAnAccessLine() {
        var p = new AccessLogParser("nginx", null, null);
        assertNull(p.parse(""));
        assertNull(p.parse("2026/10/26 19:30:27 [error] 1234#0: *1 connect() failed"));
    }

    @Test
    void routesAreNormalised() {
        var r = new RouteNormalizer();
        assertEquals("/api/orders/{id}/pay", r.normalize("/api/orders/123/pay?card=4111"));
        assertEquals("/api/users/{id}", r.normalize("/api/users/3f2b8a1c-0d4e-4a7b-9c11-2e5d6f7a8b9c"));
        assertEquals("/api/proposals/{ref}/documents", r.normalize("/api/proposals/PR-1042/documents"));
        assertEquals("/api/quotes/{ref}", r.normalize("/api/quotes/QTN-2026-14"));
        assertEquals("/reset/{token}", r.normalize("/reset/eyJhbGciOiJIUzI1NiJ9abc123def456"));
        assertEquals("/api/customers/{email}", r.normalize("/api/customers/a.b@example.com"));
        assertEquals("/api/finance/insurance-accounting-overview", r.normalize("/api/finance/insurance-accounting-overview"));
        assertEquals("/", r.normalize("/"));
        assertEquals("/files", r.normalize("http://host:8080/files"));
        r.setRules(List.of(Map.of("match", "^/files/.+$", "replace", "/files/{path}")), null);
        assertEquals("/files/{path}", r.normalize("/files/a/b/c.pdf"));
    }

    @Test
    void personalDataIsMasked() {
        var m = new Masker();
        String out = m.mask("user ravi.k@example.com mobile 9876543210 PAN ABCDE1234F aadhaar 1234 5678 9012 card 4111 1111 1111 1111 password=hunter2 Authorization: Bearer abc.def.ghi123");
        assertFalse(out.contains("ravi.k@example.com"), out);
        assertFalse(out.contains("9876543210"), out);
        assertTrue(out.contains("••••••3210"), out);
        assertFalse(out.contains("ABCDE1234F"), out);
        assertFalse(out.contains("1234 5678 9012"), out);
        assertFalse(out.contains("4111 1111 1111 1111"), out);
        assertFalse(out.contains("hunter2"), out);
        assertFalse(out.contains("abc.def.ghi123"), out);
        m.setCustom(List.of("POL-\\d+"), null);
        assertEquals("policy ****", m.mask("policy POL-998877"));
    }

    @Test
    void stackTraceLinesBelongToTheErrorAboveThem() {
        // Spring Boot (Proton core)
        assertTrue(Main.NEW_ENTRY.matcher("2026-10-26T19:46:28.183+05:30 ERROR 25311 --- [insurance-core] [nio-8080-exec-1] c.f.core.common.GlobalExceptionHandler : Unhandled error").find());
        assertFalse(Main.NEW_ENTRY.matcher("java.lang.IllegalStateException: Test fault: an unexpected error").find());
        assertFalse(Main.NEW_ENTRY.matcher("\tat com.fyntune.core.ops.FaultController.fault(FaultController.java:42)").find());
        assertFalse(Main.NEW_ENTRY.matcher("Caused by: java.net.SocketTimeoutException: timeout").find());
        // Laravel
        assertTrue(Main.NEW_ENTRY.matcher("[2026-10-26 19:46:28] production.ERROR: SQLSTATE[23000]: Integrity constraint violation").find());
        assertFalse(Main.NEW_ENTRY.matcher("[stacktrace]").find());
        assertFalse(Main.NEW_ENTRY.matcher("#0 /var/www/app/vendor/laravel/framework/src/Illuminate/Database/Connection.php(760): ...").find());
        // Apache error log
        assertTrue(Main.NEW_ENTRY.matcher("[Mon Oct 26 19:46:28.123 2026] [php:error] [pid 1234] PHP Fatal error").find());
        // request ids in log lines link the error to its request
        var m = Main.LOG_REQUEST_ID.matcher("... GlobalExceptionHandler   : Unhandled error ref=8b0671ae");
        assertTrue(m.find());
        assertEquals("8b0671ae", m.group(1));
        var j = Main.LOG_REQUEST_ID.matcher("{\"level\":\"error\",\"requestId\":\"3f2b8a1c-0d4e\",\"msg\":\"x\"}");
        assertTrue(j.find());
        assertEquals("3f2b8a1c-0d4e", j.group(1));
    }

    @Test
    void percentilesFromBuckets() {
        Hist h = new Hist();
        for (int i = 0; i < 90; i++) h.add(20);
        for (int i = 0; i < 10; i++) h.add(1500);
        assertEquals(25, h.pct(0.5));
        assertEquals(1500, h.pct(0.95));   // bucket 1600, capped at the largest seen
        assertEquals(1500, h.max);
    }

    @Test
    void aggregatorCountsAndDrainsFinishedMinutes() {
        var a = new Aggregator(new Masker());
        long t = 29_358_166L * 60_000;
        a.request(t, "GET", "/api/x/{id}", 200, 40, null, null, "access-log");
        a.request(t + 1000, "GET", "/api/x/{id}", 500, 60, "r1", null, "access-log");
        a.request(t + 2000, "POST", "/api/y", 422, 15, null, null, "access-log");
        a.request(t + 3000, "GET", "/api/x/{id}", 404, 5, null, null, "access-log");
        a.request(t + 4000, "GET", "/api/slow", 200, 2500, null, null, "access-log");
        a.request(t + 60_000, "GET", "/api/x/{id}", 200, 10, null, null, "access-log");   // next minute, still open

        var d = a.drain(29_358_167L);
        assertEquals(1, d.minutes().size());
        Map<String, Object> m = d.minutes().get(0);
        assertEquals(5L, m.get("requests"));
        assertEquals(1L, m.get("errors"));
        assertEquals(1L, m.get("refused"));   // 422 counts, 404 does not
        assertEquals(1L, m.get("slow"));
        assertEquals(3, d.endpoints().size());
        assertEquals(List.of("ERROR", "REFUSED", "SLOW"), d.events().stream().map(e -> e.get("type")).toList());
        assertEquals(1, a.drain(Long.MAX_VALUE).minutes().size());
    }
}
