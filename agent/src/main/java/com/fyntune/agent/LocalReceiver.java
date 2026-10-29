package com.fyntune.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Accepts events from in-app libraries on this machine only (127.0.0.1): POST /v1/events with a JSON array.
 * REQUEST items are counted like access-log lines; anything else is kept as an event (see PROTOCOL.md).
 */
public final class LocalReceiver {
    static final int MAX_BODY = 1024 * 1024;

    private final HttpServer server;
    private final String token;
    private final Aggregator agg;
    private final RouteNormalizer routes;

    public LocalReceiver(int port, String token, Aggregator agg, RouteNormalizer routes) throws IOException {
        this.token = token == null || token.isBlank() ? null : token;
        this.agg = agg;
        this.routes = routes;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/health", ex -> reply(ex, 200, "{\"ok\":true}"));
        server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "receiver"); t.setDaemon(true); return t; }));
    }

    public void start() {
        server.start();
        Log.info("Local receiver on http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events");
    }

    public void stop() { server.stop(0); }

    private void events(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "{\"error\":\"POST only\"}"); return; }
            if (token != null) {
                String h = ex.getRequestHeaders().getFirst("Authorization");
                if (h == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8))) {
                    reply(ex, 401, "{\"error\":\"token\"}");
                    return;
                }
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) { body = in.readNBytes(MAX_BODY + 1); }
            if (body.length > MAX_BODY) { reply(ex, 413, "{\"error\":\"over 1 MB\"}"); return; }
            List<Map<String, Object>> items = AgentConfig.JSON.readValue(body, new TypeReference<>() {});
            int n = 0;
            for (Map<String, Object> e : items) {
                String type = String.valueOf(e.getOrDefault("type", "APP")).toUpperCase(Locale.ROOT);
                long at = e.get("at") instanceof Number num ? num.longValue() : System.currentTimeMillis();
                if ("REQUEST".equals(type)) {
                    String method = String.valueOf(e.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
                    String path = String.valueOf(e.getOrDefault("path", "/"));
                    // a library usually sends its framework's route pattern already; normalising again is harmless
                    agg.request(at, method, routes.normalize(path), Checks.intOf(e.get("status"), 200),
                            e.get("ms") instanceof Number m ? m.longValue() : 0, str(e.get("requestId")), str(e.get("actor")), str(e.getOrDefault("source", "app")));
                } else {
                    e.put("type", type);
                    e.put("at", at);
                    e.putIfAbsent("source", "app");
                    if (e.get("path") instanceof String p) e.put("path", routes.normalize(p));
                    agg.event(e);
                }
                n++;
            }
            reply(ex, 200, "{\"accepted\":" + n + "}");
        } catch (Exception e) {
            reply(ex, 400, "{\"error\":\"expected a JSON array of events\"}");
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
