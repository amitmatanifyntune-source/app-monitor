package com.fyntune.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * OpenTelemetry's standard OTLP/HTTP endpoint, on this machine only (127.0.0.1, default port 4318), so apps export with
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 and no collector is needed. Traces are read (protobuf or JSON,
 * optionally gzip); metrics and logs are accepted and ignored, so an app left with those exporters on does not log
 * errors. It always answers at once: the work happens in {@link Tracer} under its own limits.
 */
public final class OtlpReceiver {
    static final int MAX_BODY = 8 * 1024 * 1024;

    private final HttpServer server;
    private final Tracer tracer;

    public OtlpReceiver(int port, Tracer tracer) throws IOException {
        this.tracer = tracer;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100);
        server.createContext("/v1/traces", this::traces);
        server.createContext("/v1/metrics", ex -> ignore(ex));
        server.createContext("/v1/logs", ex -> ignore(ex));
        server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "otlp"); t.setDaemon(true); return t; }));
    }

    public void start() {
        server.start();
        Log.info("OpenTelemetry receiver on http://127.0.0.1:" + server.getAddress().getPort() + " (OTLP/HTTP, protobuf or JSON)");
    }

    public void stop() { server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    private void traces(HttpExchange ex) throws IOException {
        boolean json = isJson(ex);
        try {
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, json, "{\"error\":\"POST only\"}"); return; }
            byte[] body = read(ex);
            if (body == null) { reply(ex, 413, json, "{\"error\":\"over 8 MB\"}"); return; }
            List<OtlpDecoder.Span> spans = json ? OtlpDecoder.json(body) : OtlpDecoder.protobuf(body);
            int taken = tracer.accept(spans, System.currentTimeMillis());
            int rejected = spans.size() - taken;
            // ExportTraceServiceResponse: empty means all accepted; a partial_success tells the SDK what was dropped
            if (json) reply(ex, 200, true, rejected == 0 ? "{}" : "{\"partialSuccess\":{\"rejectedSpans\":\"" + rejected + "\",\"errorMessage\":\"over the agent's span limit\"}}");
            else reply(ex, 200, false, null);
        } catch (Exception e) {
            Log.debug("OTLP export not understood: " + e);
            reply(ex, 400, json, "{\"error\":\"not an OTLP trace export\"}");
        }
    }

    private void ignore(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) { in.transferTo(OutputStreamSink.INSTANCE); }
        reply(ex, 200, isJson(ex), "{}");
    }

    private static boolean isJson(HttpExchange ex) {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        return ct != null && ct.toLowerCase(Locale.ROOT).contains("json");
    }

    private static byte[] read(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) { raw = in.readNBytes(MAX_BODY + 1); }
        if (raw.length > MAX_BODY) return null;
        if ("gzip".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Content-Encoding"))) {
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                byte[] b = gz.readNBytes(MAX_BODY + 1);
                return b.length > MAX_BODY ? null : b;
            }
        }
        return raw;
    }

    private static void reply(HttpExchange ex, int status, boolean json, String jsonBody) throws IOException {
        byte[] b = json && jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ex.getResponseHeaders().set("Content-Type", json ? "application/json" : "application/x-protobuf");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    /** Discards bytes. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
