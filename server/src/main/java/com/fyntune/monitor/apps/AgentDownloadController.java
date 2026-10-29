package com.fyntune.monitor.apps;

import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Map;

/** The agent jar, for administrators to copy to an app's server. Its location is app.agent-jar. */
@RestController
public class AgentDownloadController {
    private final File jar;
    private final Audit audit;

    public AgentDownloadController(@Value("${app.agent-jar}") String path, Audit audit) {
        this.jar = new File(path);
        this.audit = audit;
    }

    @GetMapping("/api/admin/agent/info")
    public Map<String, Object> info() {
        return Map.of("available", jar.isFile(), "sizeBytes", jar.isFile() ? jar.length() : 0, "lastModified", jar.isFile() ? jar.lastModified() : 0);
    }

    @GetMapping("/api/admin/agent/download")
    public ResponseEntity<Resource> download() {
        if (!jar.isFile()) throw new ApiException(HttpStatus.NOT_FOUND, "The agent has not been built on this server yet (cd agent && ./mvnw package), or app.agent-jar points elsewhere");
        audit.log("AGENT_DOWNLOADED", null, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fyntune-agent.jar\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(jar.length())
                .body(new FileSystemResource(jar));
    }
}
