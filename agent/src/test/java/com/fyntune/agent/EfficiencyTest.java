package com.fyntune.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The limits that keep the agent light on the client's server. */
class EfficiencyTest {
    static final long T0 = 1_793_000_000_000L * 1_000_000;

    static OtlpDecoder.Span span(String trace, String id, String parent, int kind, long durMs, Map<String, Object> attrs) {
        return new OtlpDecoder.Span(trace, id, parent, "s", kind, T0, T0 + durMs * 1_000_000, attrs, 0, null, "svc");
    }

    static String tid(int i) { return String.format("%032x", i); }

    @Test
    void ordinaryTracesAreLetGoAsSoonAsTheirRootArrives() {
        Tracer t = new Tracer(new Masker(), new RouteNormalizer(), new Aggregator(new Masker()), new Tracer.Settings(0, 30, 1_000_000));
        for (int i = 0; i < 5000; i++) {
            String tr = tid(i);
            t.accept(List.of(span(tr, "a" + i, "r" + i, 3, 2, Map.of("db.system", "mysql")), span(tr, "r" + i, null, 2, 10, Map.of())), 0);
        }
        Map<String, Object> st = t.stats();
        assertEquals(0, st.get("spansPending"), "nothing waits in memory for traces that will not be kept");
        assertEquals(0, st.get("tracesPending"));
        assertEquals(0L, st.get("tracesDropped"), "ordinary traces are not counted as dropped");
        assertEquals(5000L, t.drainDependencies(Long.MAX_VALUE).stream().mapToLong(d -> (Long) d.get("calls")).sum(), "still all counted as dependencies");
        // a late span of a trace already let go is not buffered again
        t.accept(List.of(span(tid(1), "late", "r1", 3, 1, Map.of())), 0);
        assertEquals(0, t.stats().get("spansPending"));
    }

    @Test
    void failedTracesAreStillKept() {
        Tracer t = new Tracer(new Masker(), new RouteNormalizer(), new Aggregator(new Masker()), new Tracer.Settings(0, 30, 1_000_000));
        String tr = tid(7);
        t.accept(List.of(span(tr, "c", "r", 3, 5, Map.of("http.request.method", "GET", "url.full", "https://x.example/a", "http.response.status_code", 503L)),
                span(tr, "r", null, 2, 20, Map.of())), 0);
        t.tick(Tracer.QUIET_MS + 1);
        assertEquals(1, t.drainTraces().size());
    }

    @Test
    void spansWaitingForARootAreCapped() {
        Tracer t = new Tracer(new Masker(), new RouteNormalizer(), new Aggregator(new Masker()), new Tracer.Settings(1, 30, 1_000_000));
        // children whose root never comes (a broken exporter): memory stays bounded
        for (int i = 0; i < 1500; i++) {
            List<OtlpDecoder.Span> kids = new ArrayList<>();
            for (int k = 0; k < 20; k++) kids.add(span(tid(i), "k" + k, "never", 3, 1, Map.of()));
            t.accept(kids, 0);
        }
        Map<String, Object> st = t.stats();
        assertTrue((Integer) st.get("spansPending") <= Tracer.MAX_PENDING_SPANS, st.toString());
        assertTrue((Long) st.get("spansDropped") > 0);
    }

    @Test
    void decoderSkipsAttributesTheMonitorDoesNotUse() throws Exception {
        String json = """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"svc"}},{"key":"host.name","value":{"stringValue":"h"}}]},
                  "scopeSpans":[{"spans":[{"traceId":"%s","spanId":"00f067aa0ba902b7","name":"GET","kind":2,"startTimeUnixNano":"1","endTimeUnixNano":"2",
                    "attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},{"key":"user_agent.original","value":{"stringValue":"Mozilla"}},
                                  {"key":"client.address","value":{"stringValue":"10.0.0.5"}},{"key":"http.request.header.x_request_id","value":{"stringValue":"abc123"}}]}]}]}]}
                """.formatted(tid(1));
        OtlpDecoder.Span s = OtlpDecoder.json(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
        assertEquals(Set.of("http.request.method", "http.request.header.x_request_id"), s.attrs().keySet());
        assertEquals("svc", s.service());
    }

    @Test
    void tailerHandlesPartialLongAndWindowsLines(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "");
        FileTailer t = new FileTailer(log, new State(dir.resolve("spool")));
        List<String> got = new ArrayList<>();
        t.poll(got::add);
        Files.writeString(log, "one\r\ntwo\npart", StandardOpenOption.APPEND);
        t.poll(got::add);
        assertEquals(List.of("one", "two"), got, "the unfinished line waits");
        String huge = "x".repeat(FileTailer.BUF * 2);
        Files.writeString(log, "ial\n" + huge + "\nafter\n", StandardOpenOption.APPEND);
        t.poll(got::add);
        assertEquals("partial", got.get(2));
        assertEquals(FileTailer.MAX_LINE, got.get(3).length(), "a very long line is cut");
        assertEquals("after", got.get(4));
        // a backlog bigger than one poll is worked through over several polls
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 450_000; i++) many.append("line ").append(i).append('\n');
        Files.writeString(log, many.toString(), StandardOpenOption.APPEND);
        got.clear();
        int polls = 0;
        while (got.size() < 450_000 && polls < 20) { t.poll(got::add); polls++; }
        assertEquals(450_000, got.size());
        assertEquals("line 449999", got.get(got.size() - 1));
        assertTrue(polls > 1, "split over polls");
    }

    @Test
    void endpointsPerMinuteAreCapped() {
        Aggregator a = new Aggregator(new Masker());
        for (int i = 0; i < Aggregator.MAX_ENDPOINTS_PER_MINUTE + 500; i++) a.request(0, "GET", "/scan/" + i, 404, 1, null, null, "access-log");
        var d = a.drain(Long.MAX_VALUE);
        assertEquals(Aggregator.MAX_ENDPOINTS_PER_MINUTE + 1, d.endpoints().size());
        assertTrue(d.endpoints().stream().anyMatch(e -> "(other paths)".equals(e.get("pattern"))));
        assertEquals(Aggregator.MAX_ENDPOINTS_PER_MINUTE + 500L, d.minutes().get(0).get("requests"), "every request still counted");
    }

    @Test
    void batchesAreGzippedOnDiskAndOnTheWireWhenTheServerAcceptsIt(@TempDir Path dir) throws Exception {
        List<String> encodings = new CopyOnWriteArrayList<>();
        List<Long> seqs = new CopyOnWriteArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] gzipServer = {false};
        srv.createContext("/ingest/v1/config", ex -> {
            byte[] r = ("{\"slowMs\":2000,\"gzip\":" + gzipServer[0] + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        srv.createContext("/ingest/v1/batch", ex -> {
            String enc = ex.getRequestHeaders().getFirst("Content-Encoding");
            encodings.add(String.valueOf(enc));
            InputStream in = "gzip".equals(enc) ? new GZIPInputStream(ex.getRequestBody()) : ex.getRequestBody();
            seqs.add(((Number) AgentConfig.JSON.readValue(in, Map.class).get("seq")).longValue());
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes());
            ex.close();
        });
        srv.start();
        try {
            AgentConfig cfg = new AgentConfig(new AgentConfig.Server("http://127.0.0.1:" + srv.getAddress().getPort(), "ag_test", "k"),
                    dir.toString(), 1, null, null, null, false, null, null, null, null, null, null, null, null);
            Transport tr = new Transport(cfg, HttpClient.newHttpClient());
            Map<String, Object> big = new LinkedHashMap<>(Map.of("v", 1, "seq", 1L));
            big.put("events", Collections.nCopies(500, Map.of("type", "ERROR", "message", "the same message again and again")));
            tr.enqueue(1, big);
            try (var files = Files.list(dir.resolve("outbox"))) {
                Path f = files.findFirst().orElseThrow();
                assertTrue(f.toString().endsWith(".json.gz"));
                assertTrue(Files.size(f) < 2000, "compressed on disk: " + Files.size(f) + " bytes");
            }
            tr.pullConfig();               // an older server: no gzip
            tr.flush();
            gzipServer[0] = true;
            tr.pullConfig();               // a 0.2 server: gzip
            tr.enqueue(2, Map.of("v", 1, "seq", 2L));
            tr.flush();
            assertEquals(List.of(1L, 2L), seqs);
            assertEquals(List.of("null", "gzip"), encodings);
        } finally {
            srv.stop(0);
        }
    }
}
