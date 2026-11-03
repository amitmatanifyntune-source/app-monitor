package com.fyntune.agent;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.type.TypeReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Sends batches to the monitoring server. Every batch is first written to the spool directory and deleted only once
 * the server has accepted it, so nothing is lost while the server or the network is down; batches are sent oldest
 * first. When the spool passes its size limit the oldest batches are dropped (and that is logged).
 */
public final class Transport {
    private final AgentConfig cfg;
    private final HttpClient http;
    private final Path spool;
    private volatile boolean serverReachable = true;
    /** Set when the server's settings say it unpacks gzip (server 0.2+); older servers get plain JSON. */
    private volatile boolean serverGzip;

    public Transport(AgentConfig cfg, HttpClient http) throws IOException {
        this.cfg = cfg;
        this.http = http;
        this.spool = Path.of(cfg.spool(), "outbox");
        Files.createDirectories(spool);
    }

    public static HttpClient client(AgentConfig.Tls tls) throws Exception {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null && (tls.trustStore() != null || tls.keyStore() != null)) {
            TrustManagerFactory tmf = null;
            KeyManagerFactory kmf = null;
            if (tls.trustStore() != null) {
                KeyStore ts = load(tls.trustStore(), tls.trustStorePassword());
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
            }
            if (tls.keyStore() != null) {
                KeyStore ks = load(tls.keyStore(), tls.keyStorePassword());
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, tls.keyStorePassword() == null ? new char[0] : tls.keyStorePassword().toCharArray());
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            b.sslContext(ctx);
        }
        return b.build();
    }

    private static KeyStore load(String path, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance(path.endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = new FileInputStream(path)) {
            ks.load(in, password == null ? null : password.toCharArray());
        }
        return ks;
    }

    /** Puts a batch in the spool, gzip-compressed (typically a tenth of the size). Its file name sorts in send order. */
    public void enqueue(long seq, Map<String, Object> batch) throws IOException {
        Path f = spool.resolve(String.format("batch-%015d.json.gz", seq));
        Path tmp = spool.resolve(f.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)), 8192)) {
            AgentConfig.JSON.writeValue(out, batch);
        }
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        trim();
    }

    public void setServerGzip(boolean v) { serverGzip = v; }

    /** Sends spooled batches, oldest first, until one fails. */
    public int flush() {
        int sent = 0;
        for (Path f : pending()) {
            try {
                byte[] body = Files.readAllBytes(f);
                boolean gz = f.getFileName().toString().endsWith(".gz");
                if (gz && !serverGzip) {   // an older server: unpack before sending
                    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) { body = in.readAllBytes(); }
                    gz = false;
                }
                HttpRequest.Builder rb = request("/ingest/v1/batch").timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
                if (gz) rb.header("Content-Encoding", "gzip");
                HttpResponse<String> res = http.send(rb.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    Files.deleteIfExists(f);
                    sent++;
                    if (!serverReachable) Log.info("Monitoring server reachable again; sending spooled batches");
                    serverReachable = true;
                    continue;
                }
                if (res.statusCode() == 401) {
                    Log.warn("The server refused this agent's id or key (401). Check server.agentId/agentKey, or whether the agent was revoked.");
                } else if (res.statusCode() == 400) {
                    // a batch the server cannot read will never be accepted: set it aside instead of blocking the rest
                    Path bad = f.resolveSibling(f.getFileName() + ".rejected");
                    Files.move(f, bad, StandardCopyOption.REPLACE_EXISTING);
                    Log.warn("The server rejected " + f.getFileName() + " (400): kept as " + bad.getFileName() + " — " + res.body());
                    continue;
                } else {
                    Log.warn("Monitoring server answered " + res.statusCode() + "; will retry");
                }
                serverReachable = false;
                break;
            } catch (Exception e) {
                if (serverReachable) Log.warn("Cannot reach the monitoring server (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); spooling until it is back");
                serverReachable = false;
                break;
            }
        }
        return sent;
    }

    /** The server's settings for this agent, or null if it cannot be reached now. */
    public RemoteConfig pullConfig() {
        try {
            HttpResponse<String> res = http.send(request("/ingest/v1/config").timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                Log.warn("Could not fetch settings from the server (HTTP " + res.statusCode() + ")");
                return null;
            }
            RemoteConfig rc = AgentConfig.JSON.readValue(res.body(), new TypeReference<RemoteConfig>() {});
            serverGzip = Boolean.TRUE.equals(rc.gzip());
            return rc;
        } catch (Exception e) {
            Log.debug("Settings not fetched: " + e.getMessage());
            return null;
        }
    }

    public int pendingCount() { return pending().size(); }

    private HttpRequest.Builder request(String path) {
        String base = cfg.server().url().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Agent-Id", cfg.server().agentId())
                .header("X-Agent-Key", cfg.server().agentKey())
                .header("User-Agent", "fyntune-agent/" + Main.VERSION);
    }

    private List<Path> pending() {
        try (Stream<Path> s = Files.list(spool)) {
            return s.filter(p -> p.getFileName().toString().matches("batch-\\d+\\.json(\\.gz)?")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void trim() throws IOException {
        List<Path> files = pending();
        long total = 0;
        for (Path p : files) total += Files.size(p);
        int dropped = 0;
        for (Path p : files) {
            if (total <= cfg.spoolMaxBytes()) break;
            total -= Files.size(p);
            Files.deleteIfExists(p);
            dropped++;
        }
        if (dropped > 0) Log.warn("Spool over " + cfg.spoolMaxBytes() / 1048576 + " MB: dropped the " + dropped + " oldest batch(es)");
    }
}
