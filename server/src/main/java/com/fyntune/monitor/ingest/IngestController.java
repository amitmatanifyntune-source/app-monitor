package com.fyntune.monitor.ingest;

import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;

/** Where agents send their batches and fetch their settings. Agent key only; no user sign-in. */
@RestController
@RequestMapping("/ingest/v1")
public class IngestController {
    private final AgentAuth auth;
    private final IngestService svc;
    private final File jar;
    private final Audit audit;
    private long jarStamp = -1;
    private String jarVersion, jarSha256;

    public IngestController(AgentAuth auth, IngestService svc, @Value("${app.agent-jar}") String jarPath, Audit audit) {
        this.auth = auth;
        this.svc = svc;
        this.jar = new File(jarPath);
        this.audit = audit;
    }

    @PostMapping("/batch")
    public IngestService.Result batch(@RequestHeader(value = "X-Agent-Id", required = false) String id,
                                      @RequestHeader(value = "X-Agent-Key", required = false) String key, @RequestBody Batch b) {
        return svc.store(auth.check(id, key), b);
    }

    @GetMapping("/config")
    public Map<String, Object> config(@RequestHeader(value = "X-Agent-Id", required = false) String id,
                                      @RequestHeader(value = "X-Agent-Key", required = false) String key) {
        return svc.config(auth.check(id, key).appId());
    }

    /**
     * The current agent jar, for an agent's server to upgrade itself (enable-otel.sh, update scripts) with the agent's own
     * id and key: no administrator password on the client's server. Headers give its version and SHA-256.
     */
    @GetMapping("/agent.jar")
    public ResponseEntity<Resource> agentJar(@RequestHeader(value = "X-Agent-Id", required = false) String id,
                                             @RequestHeader(value = "X-Agent-Key", required = false) String key) {
        AgentAuth.Agent a = auth.check(id, key);
        if (!jar.isFile()) throw new ApiException(HttpStatus.NOT_FOUND, "No agent jar on this server (app.agent-jar)");
        describeJar();
        audit.log("agent:" + a.id(), "AGENT_JAR_FETCHED", null, jarVersion);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fyntune-agent.jar\"")
                .header("X-Agent-Version", jarVersion)
                .header("X-Agent-Sha256", jarSha256)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(jar.length())
                .body(new FileSystemResource(jar));
    }

    /** Version (from the manifest) and SHA-256 of the jar, worked out again only when the file changes. */
    private synchronized void describeJar() {
        if (jar.lastModified() == jarStamp) return;
        try (JarFile jf = new JarFile(jar)) {
            String v = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("Implementation-Version");
            jarVersion = v == null ? "unknown" : v;
        } catch (Exception e) {
            jarVersion = "unknown";
        }
        try (InputStream in = Files.newInputStream(jar.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            jarSha256 = HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read the agent jar");
        }
        jarStamp = jar.lastModified();
    }
}
