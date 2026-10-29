package com.fyntune.monitor.ingest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Where agents send their batches and fetch their settings. Agent key only; no user sign-in. */
@RestController
@RequestMapping("/ingest/v1")
public class IngestController {
    private final AgentAuth auth;
    private final IngestService svc;

    public IngestController(AgentAuth auth, IngestService svc) {
        this.auth = auth;
        this.svc = svc;
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
}
