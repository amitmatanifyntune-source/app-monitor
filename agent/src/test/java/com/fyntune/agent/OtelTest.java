package com.fyntune.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtelTest {
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final long T0 = 1_793_000_000_000L * 1_000_000;   // ns

    // ------------------------------------------------------------------ a tiny protobuf writer, for the tests only

    static final class Pb {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Pb varint(int field, long v) { tag(field, 0); raw(v); return this; }
        Pb fixed64(int field, long v) { tag(field, 1); out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()); return this; }
        Pb bytes(int field, byte[] b) { tag(field, 2); raw(b.length); out.writeBytes(b); return this; }
        Pb str(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }
        Pb msg(int field, Pb m) { return bytes(field, m.out.toByteArray()); }
        void tag(int f, int wt) { raw(((long) f << 3) | wt); }
        void raw(long v) { while ((v & ~0x7fL) != 0) { out.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; } out.write((int) v); }
        byte[] bytes() { return out.toByteArray(); }
    }

    static Pb kv(String k, String v) { return new Pb().str(1, k).msg(2, new Pb().str(1, v)); }
    static Pb kvInt(String k, long v) { return new Pb().str(1, k).msg(2, new Pb().varint(3, v)); }

    static Pb span(String id, String parent, String name, int kind, long startMs, long durMs, int status, Pb... attrs) {
        Pb s = new Pb().bytes(1, HexFormat.of().parseHex(TRACE)).bytes(2, HexFormat.of().parseHex(id));
        if (parent != null) s.bytes(4, HexFormat.of().parseHex(parent));
        s.str(5, name).varint(6, kind).fixed64(7, T0 + startMs * 1_000_000).fixed64(8, T0 + (startMs + durMs) * 1_000_000);
        for (Pb a : attrs) s.msg(9, a);
        if (status != 0) s.msg(15, new Pb().str(2, "boom").varint(3, status));
        return s;
    }

    /** POST /claims/42/submit (2.3 s) → one SQL query and one call to the insurer's API, which fails. */
    static byte[] export() {
        Pb scope = new Pb()
                .msg(2, span("00f067aa0ba902b7", null, "POST /claims/{id}/submit", 2, 0, 2300, 0,
                        kv("http.request.method", "POST"), kv("http.route", "/claims/{id}/submit"), kvInt("http.response.status_code", 502),
                        kv("http.request.header.x_request_id", "9f2c1ab07e3d")))
                .msg(2, span("1111111111111111", "00f067aa0ba902b7", "SELECT", 3, 10, 40, 0,
                        kv("db.system", "mysql"), kv("db.name", "claims"), kv("server.address", "127.0.0.1"),
                        kv("db.statement", "SELECT * FROM policies WHERE holder_email = 'asha@example.com' AND id IN (1, 2, 3)")))
                .msg(2, span("2222222222222222", "00f067aa0ba902b7", "POST", 3, 100, 1900, 2,
                        kv("http.request.method", "POST"), kv("url.full", "https://api.insurer.example/v2/claims/98765?token=abc"),
                        kvInt("http.response.status_code", 503)));
        Pb resource = new Pb().msg(1, kv("service.name", "claims-api"));
        return new Pb().msg(1, new Pb().msg(1, resource).msg(2, scope)).bytes();
    }

    static Tracer tracer(double ratio) {
        return new Tracer(new Masker(), new RouteNormalizer(), new Aggregator(new Masker()), new Tracer.Settings(ratio, 30, 50_000));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void decodesProtobufExport() throws Exception {
        List<OtlpDecoder.Span> spans = OtlpDecoder.protobuf(export());
        assertEquals(3, spans.size());
        OtlpDecoder.Span root = spans.get(0);
        assertEquals(TRACE, root.traceId());
        assertEquals("00f067aa0ba902b7", root.spanId());
        assertNull(root.parentId());
        assertEquals(OtlpDecoder.SERVER, root.kind());
        assertEquals(2_300_000, root.durationUs());
        assertEquals("claims-api", root.service());
        assertEquals(502L, root.attrs().get("http.response.status_code"));
        assertEquals("00f067aa0ba902b7", spans.get(1).parentId());
        assertTrue(spans.get(2).error());
    }

    @Test
    void decodesJsonExport() throws Exception {
        String json = """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"billing"}}]},
                  "scopeSpans":[{"spans":[{"traceId":"%s","spanId":"00f067aa0ba902b7","name":"GET /invoices/{id}","kind":2,
                    "startTimeUnixNano":"%d","endTimeUnixNano":"%d","status":{"code":2,"message":"failed"},
                    "attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},{"key":"http.response.status_code","value":{"intValue":"500"}}]}]}]}]}
                """.formatted(TRACE, T0, T0 + 150_000_000);
        List<OtlpDecoder.Span> spans = OtlpDecoder.json(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, spans.size());
        assertEquals("billing", spans.get(0).service());
        assertEquals(150_000, spans.get(0).durationUs());
        assertTrue(spans.get(0).error());
        assertEquals(500L, spans.get(0).attrs().get("http.response.status_code"));
    }

    @Test
    void countsDependenciesAndKeepsTheFailedTrace() throws Exception {
        Tracer t = tracer(0);
        assertEquals(3, t.accept(OtlpDecoder.protobuf(export()), 0));
        t.tick(QUIET_LATER);
        List<Map<String, Object>> deps = t.drainDependencies(Long.MAX_VALUE);
        assertEquals(2, deps.size());
        Map<String, Object> db = deps.stream().filter(d -> "db".equals(d.get("kind"))).findFirst().orElseThrow();
        assertEquals("mysql claims @ 127.0.0.1", db.get("target"));
        assertEquals("SELECT policies", db.get("operation"));
        assertEquals(1L, db.get("calls"));
        Map<String, Object> api = deps.stream().filter(d -> "http".equals(d.get("kind"))).findFirst().orElseThrow();
        assertEquals("api.insurer.example", api.get("target"));
        assertEquals("POST /v2/claims/{id}", api.get("operation"));
        assertEquals(1L, api.get("errors"));
        assertEquals(1900L, api.get("maxMs"));

        List<Map<String, Object>> traces = t.drainTraces();
        assertEquals(1, traces.size(), "failed trace kept even with sampling off");
        Map<String, Object> tr = traces.get(0);
        assertEquals("error", tr.get("reason"));
        assertEquals("POST /claims/{id}/submit", tr.get("name"));
        assertEquals(2300L, tr.get("durationMs"));
        assertEquals(502, tr.get("status"));
        assertEquals("9f2c1ab07e3d", tr.get("requestId"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> spans = (List<Map<String, Object>>) tr.get("spans");
        assertEquals(3, spans.size());
        @SuppressWarnings("unchecked") Map<String, Object> sqlAttrs = (Map<String, Object>) spans.get(1).get("attrs");
        String sql = (String) sqlAttrs.get("db.statement");
        assertFalse(sql.contains("asha"), "literal values removed before masking: " + sql);
        assertTrue(sql.contains("holder_email = ? AND id IN (?)"), sql);
        @SuppressWarnings("unchecked") Map<String, Object> apiAttrs = (Map<String, Object>) spans.get(2).get("attrs");
        assertFalse(String.valueOf(apiAttrs.get("url.full")).contains("token"), "query string dropped");
        assertEquals(100_000L, spans.get(2).get("startUs"));
    }

    static final long QUIET_LATER = Tracer.QUIET_MS + 1;

    @Test
    void ordinaryFastTracesFollowTheSampleAndTheLimits() {
        // with ratio 0 a fast, successful trace is counted in dependencies but not kept
        Tracer none = tracer(0);
        none.accept(List.of(fast("aa")), 0);
        none.tick(QUIET_LATER);
        assertTrue(none.drainTraces().isEmpty());

        Tracer all = tracer(1);
        for (int i = 0; i < 50; i++) all.accept(List.of(fast(String.format("%02x", i))), 0);
        all.tick(QUIET_LATER);
        assertEquals(10, all.drainTraces().size(), "sampled traces capped at a third of maxTracesPerMinute");
        Map<String, Object> st = all.stats();
        assertEquals(50L, st.get("spansReceived"));
        assertEquals(10L, st.get("tracesKept"));
        assertEquals(40L, st.get("tracesDropped"));
    }

    @Test
    void spanLimitDropsWithoutBlocking() {
        Tracer t = new Tracer(new Masker(), new RouteNormalizer(), new Aggregator(new Masker()), new Tracer.Settings(0, 30, 100));
        int taken = 0;
        for (int i = 0; i < 150; i++) taken += t.accept(List.of(fast(String.format("%02x", i % 256))), 0);
        assertEquals(100, taken);
        assertEquals(50L, t.stats().get("spansDropped"));
    }

    @Test
    void sqlSummaryAndSampling() {
        assertEquals("UPDATE invoices", Tracer.sqlSummary(sp(Map.of("db.statement", "update `invoices` set paid = 1 where id = 7"))));
        assertEquals("INSERT audit_log", Tracer.sqlSummary(sp(Map.of("db.statement", "INSERT INTO audit_log (a) VALUES ('x')"))));
        assertEquals("SELECT", Tracer.sqlSummary(sp(Map.of("db.operation", "select"))));
        assertEquals("SELECT ? FROM t WHERE a = ?", Tracer.sanitizeSql("SELECT 1 FROM t WHERE a = 'it''s'"));
        assertTrue(Tracer.sampled(TRACE, 1));
        assertFalse(Tracer.sampled(TRACE, 0));
        assertEquals(Tracer.sampled(TRACE, 0.5), Tracer.sampled(TRACE, 0.5), "same decision every time");
    }

    @Test
    void receiverAcceptsProtobufAndJsonOverHttp() throws Exception {
        Tracer t = tracer(1);
        OtlpReceiver r = new OtlpReceiver(0, t);
        r.start();
        try {
            HttpClient c = HttpClient.newHttpClient();
            var pb = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + "/v1/traces"))
                    .header("Content-Type", "application/x-protobuf").POST(HttpRequest.BodyPublishers.ofByteArray(export())).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, pb.statusCode());
            var bad = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + "/v1/traces"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("not json")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            var metrics = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + "/v1/metrics"))
                    .header("Content-Type", "application/x-protobuf").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2})).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode(), "metrics accepted and ignored");
            assertEquals(3L, t.stats().get("spansReceived"));
        } finally {
            r.stop();
        }
    }

    // ------------------------------------------------------------------ helpers

    static OtlpDecoder.Span fast(String idSuffix) {
        String trace = TRACE.substring(0, 30) + idSuffix;
        return new OtlpDecoder.Span(trace, "00f067aa0ba902" + idSuffix, null, "GET /health", 2, T0, T0 + 5_000_000, Map.of(), 0, null, "svc");
    }

    static OtlpDecoder.Span sp(Map<String, Object> attrs) {
        return new OtlpDecoder.Span(TRACE, "1111111111111111", "00f067aa0ba902b7", "q", 3, T0, T0 + 1_000_000, attrs, 0, null, "svc");
    }
}
