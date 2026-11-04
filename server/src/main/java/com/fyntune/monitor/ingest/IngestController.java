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
    private final File jar, kit;
    private final Audit audit;
    private long kitStamp = -1;
    private byte[] kitZip;
    private String kitSha256;
    private long jarStamp = -1;
    private String jarVersion, jarSha256;

    public IngestController(AgentAuth auth, IngestService svc, @Value("${app.agent-jar}") String jarPath,
                            @Value("${app.agent-kit}") String kitPath, Audit audit) {
        this.auth = auth;
        this.svc = svc;
        this.jar = new File(jarPath);
        this.kit = new File(kitPath);
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

    /** Kit files never handed out: an agent's own config and keys, runtime files, the jar (fetched separately). */
    private static final java.util.Set<String> KIT_SKIP_EXT = java.util.Set.of(".jar", ".log", ".bak");
    private static final java.util.Set<String> KIT_SKIP = java.util.Set.of("agent.json", ".env", "admin-password", "registration.json");

    /**
     * The client install kit (scripts, Dockerfile, compose file, README) as a zip, for {@code fyntune-update} on an
     * agent's server: with the agent's own id and key, so servers update their scripts as well as the agent, without
     * access to the repository. Header X-Kit-Sha256 is the zip's checksum; X-Agent-Version the jar it goes with.
     */
    @GetMapping("/agent-kit.zip")
    public ResponseEntity<byte[]> agentKit(@RequestHeader(value = "X-Agent-Id", required = false) String id,
                                           @RequestHeader(value = "X-Agent-Key", required = false) String key) {
        AgentAuth.Agent a = auth.check(id, key);
        byte[] zip = kitZip();
        if (jar.isFile()) describeJar();
        audit.log("agent:" + a.id(), "AGENT_KIT_FETCHED", null, jarVersion);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fyntune-agent-kit.zip\"")
                .header("X-Kit-Sha256", kitSha256)
                .header("X-Agent-Version", jarVersion == null ? "unknown" : jarVersion)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    /** The kit folder's regular files, zipped once and again only when one of them changes. */
    private synchronized byte[] kitZip() {
        File[] files = kit.isDirectory() ? kit.listFiles(File::isFile) : null;
        if (files == null || files.length == 0) throw new ApiException(HttpStatus.NOT_FOUND, "No agent kit on this server (app.agent-kit)");
        java.util.Arrays.sort(files);
        long stamp = 0;
        for (File f : files) stamp = Math.max(stamp, f.lastModified()) + f.length();
        if (stamp == kitStamp && kitZip != null) return kitZip;
        try (var bos = new java.io.ByteArrayOutputStream(); var zos = new java.util.zip.ZipOutputStream(bos)) {
            for (File f : files) {
                String n = f.getName();
                if (KIT_SKIP.contains(n) || n.startsWith(".") || KIT_SKIP_EXT.stream().anyMatch(n::endsWith)) continue;
                var e = new java.util.zip.ZipEntry(n);
                e.setTime(f.lastModified());
                zos.putNextEntry(e);
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
            zos.finish();
            kitZip = bos.toByteArray();
            kitSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(kitZip));
            kitStamp = stamp;
            return kitZip;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot pack the agent kit");
        }
    }
}
