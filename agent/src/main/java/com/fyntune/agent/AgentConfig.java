package com.fyntune.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The local agent.json. Only this file holds database credentials; they are never sent to the server. Everything the
 * server can change (checks, slow threshold, masking and path rules) comes from {@link RemoteConfig} as well.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(
        Server server,
        String spoolDir,
        Integer spoolMaxMb,
        List<AccessLog> accessLogs,
        List<AppLog> appLogs,
        Db db,
        boolean allowServerSqlChecks,
        List<Map<String, Object>> checks,
        Host host,
        LocalReceiver localReceiver,
        Tls tls,
        Integer slowMs,
        List<String> mask,
        List<Map<String, String>> routeRules) {

    /** url: the monitoring server, e.g. https://monitor.example.com. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Server(String url, String agentId, String agentKey) {}

    /**
     * format: nginx | apache | tomcat | custom. timeUnit (s, ms, us) overrides the format's default unit for the
     * response time. regex (custom only) needs named groups method, path, status and time, optionally rid and ts.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessLog(String path, String format, String timeUnit, String regex) {}

    /** An app's own log file; lines matching errorPattern are reported as LOG_ERROR. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppLog(String path, String errorPattern) {}

    /** engine: postgres | mysql. url is a JDBC URL. Use a read-only monitoring user (see README). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Db(String engine, String url, String user, String password) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Host(List<String> disks, List<Proc> processes, List<Port> ports) {}

    /** match: text that the process's command line contains, e.g. php-fpm, or java.*insurance-core (a regex). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Proc(String name, String match) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Port(String name, String host, int port) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalReceiver(Boolean enabled, Integer port, String token) {}

    /** Optional: a trust store for a private CA, and a key store for mutual TLS. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tls(String trustStore, String trustStorePassword, String keyStore, String keyStorePassword) {}

    public static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static AgentConfig load(File f) throws IOException {
        AgentConfig c = JSON.readValue(f, AgentConfig.class);
        if (c.server() == null || c.server().url() == null || c.server().agentId() == null || c.server().agentKey() == null)
            throw new IllegalArgumentException("agent.json: server.url, server.agentId and server.agentKey are required");
        return c;
    }

    public String spool() { return spoolDir == null ? "./spool" : spoolDir; }
    public long spoolMaxBytes() { return (spoolMaxMb == null ? 200L : spoolMaxMb) * 1024 * 1024; }
}
