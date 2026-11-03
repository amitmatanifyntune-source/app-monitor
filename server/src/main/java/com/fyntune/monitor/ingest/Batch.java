package com.fyntune.monitor.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** One agent batch, as described in PROTOCOL.md. Unknown fields are ignored, so newer agents can add some. */
public record Batch(int v, long seq, String agentVersion, String host, Long sentAt,
                    List<Minute> minutes, List<Endpoint> endpoints, List<Event> events, List<Check> checks, Db db, JsonNode hostStats, JsonNode spec,
                    List<Dep> deps, List<JsonNode> traces) {

    public record Minute(long minute, long requests, long errors, long refused, long slow, long totalMs, long maxMs, long[] hist) {}

    public record Endpoint(long minute, String method, String pattern, long requests, long errors, long refused, long slow,
                           long totalMs, long maxMs, long[] hist) {}

    public record Event(Long at, String type, String source, String requestId, String method, String path, Integer status, Long ms,
                        String actor, String message, String detail) {}

    public record Check(String name, String kind, Long at, boolean ok, Long ms, Double value, String message, List<String> refs) {}

    public record Db(Long at, String engine, String version, JsonNode snapshot) {}

    /** An outbound call per minute, from OpenTelemetry spans (agent 0.2+). */
    public record Dep(long minute, String kind, String target, String operation, long calls, long errors, long totalMs, long maxMs, long[] hist) {}
}
