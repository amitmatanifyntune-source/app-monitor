package com.fyntune.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyntune.monitor.common.Hist;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole path on a real Postgres: sign in, add an app and agent, ingest batches, read the views, check access. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitorFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Flyway flyway;
    @Autowired com.fyntune.monitor.auth.AdminSeeder seeder;

    String admin;

    @BeforeAll
    void fresh() throws Exception {
        flyway.clean();
        flyway.migrate();
        seeder.run(null);
        admin = token("admin", "Password@123");
    }

    String token(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    JsonNode call(MockHttpServletRequestBuilder r, String token, Object body, int expect) throws Exception {
        r.header("Authorization", "Bearer " + token);
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String s = mvc.perform(r).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    JsonNode ingest(String id, String key, Map<String, Object> batch, int expect) throws Exception {
        String s = mvc.perform(post("/ingest/v1/batch").header("X-Agent-Id", id).header("X-Agent-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch))).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    @Test
    void endToEnd() throws Exception {
        call(post("/api/admin/apps"), admin, Map.of("code", "shop", "name", "Shop (Laravel)", "environment", "uat"), 200);
        call(post("/api/admin/apps"), admin, Map.of("code", "other", "name", "Other app"), 200);
        call(post("/api/admin/apps"), admin, Map.of("code", "Bad Code", "name", "x"), 400);
        call(post("/api/admin/apps/shop/checks"), admin, Map.of("name", "Health", "kind", "HTTP", "health", true,
                "config", Map.of("url", "https://shop.example.com/health", "expectStatus", 200)), 200);
        call(post("/api/admin/apps/shop/checks"), admin, Map.of("name", "Bad", "kind", "SQL", "config", Map.of("sql", "DELETE FROM orders")), 400);

        JsonNode reg = call(post("/api/admin/apps/shop/agents"), admin, Map.of("name", "web-1", "serverUrl", "https://monitor.example.com"), 200);
        String id = reg.get("agentId").asText(), key = reg.get("agentKey").asText();
        assertEquals("https://monitor.example.com", reg.at("/agentJson/server/url").asText());

        assertEquals("NO_DATA", call(get("/api/apps/shop/summary"), admin, null, 200).get("status").asText());

        // wrong key and unknown agent are refused alike
        ingest(id, "wrong", Map.of("v", 1, "seq", 1), 401);
        ingest("ag_nope", key, Map.of("v", 1, "seq", 1), 401);

        // settings for the agent: the health check, without anything private
        String cfg = mvc.perform(get("/ingest/v1/config").header("X-Agent-Id", id).header("X-Agent-Key", key)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(cfg.contains("https://shop.example.com/health"), cfg);

        long minute = System.currentTimeMillis() / 60000 - 1;
        long[] h1 = Hist.empty(); h1[2] = 8; h1[7] = 2;   // 8 within 50 ms, 2 within 1600 ms
        Map<String, Object> batch = Map.of("v", 1, "seq", 1, "agentVersion", "0.1.0", "host", "web-1",
                "minutes", List.of(Map.of("minute", minute, "requests", 10, "errors", 1, "refused", 2, "slow", 0, "totalMs", 3400, "maxMs", 1500, "hist", h1)),
                "endpoints", List.of(Map.of("minute", minute, "method", "POST", "pattern", "/api/orders/{id}/pay", "requests", 10, "errors", 1, "refused", 2,
                        "slow", 0, "totalMs", 3400, "maxMs", 1500, "hist", h1)),
                "events", List.of(Map.of("type", "ERROR", "source", "access-log", "requestId", "abc123", "method", "POST",
                        "path", "/api/orders/{id}/pay", "status", 500, "ms", 230, "message", "HTTP 500")),
                "checks", List.of(Map.of("name", "Health", "kind", "HTTP", "ok", false, "ms", 12, "message", "HTTP 503 (expected 200)")),
                "db", Map.of("engine", "mysql", "version", "8.0.36", "snapshot", Map.of("up", true, "summary", "120 MB · 12 of 151 connections", "warn", false)),
                "hostStats", Map.of("cores", 4, "cpu", 0.25, "memUsedMb", 1000, "memTotalMb", 4000));
        assertTrue(ingest(id, key, batch, 200).get("accepted").asBoolean());
        // a resend of the same batch is not counted again
        assertFalse(ingest(id, key, batch, 200).get("accepted").asBoolean());

        // a revoked agent (checked at the end)
        JsonNode reg2 = call(post("/api/admin/apps/shop/agents"), admin, Map.of("name", "web-2"), 200);
        String id2 = reg2.get("agentId").asText(), key2 = reg2.get("agentKey").asText();
        call(post("/api/admin/apps/shop/agents/" + id2 + "/revoke"), admin, null, 200);

        // a second agent on the same app adds to the same minute; percentiles come from the added histograms
        JsonNode reg3 = call(post("/api/admin/apps/shop/agents"), admin, Map.of("name", "web-3"), 200);
        long[] h2 = Hist.empty(); h2[2] = 10;
        ingest(reg3.get("agentId").asText(), reg3.get("agentKey").asText(), Map.of("v", 1, "seq", 1,
                "minutes", List.of(Map.of("minute", minute, "requests", 10, "errors", 0, "refused", 0, "slow", 0, "totalMs", 300, "maxMs", 40, "hist", h2)),
                "endpoints", List.of(Map.of("minute", minute, "method", "POST", "pattern", "/api/orders/{id}/pay", "requests", 10, "errors", 0, "refused", 0,
                        "slow", 0, "totalMs", 300, "maxMs", 40, "hist", h2))), 200);

        JsonNode t = call(get("/api/apps/shop/traffic?minutes=5"), admin, null, 200);
        assertEquals(20, t.at("/last5/requests").asLong());
        assertEquals(1, t.at("/last5/errors").asLong());
        assertEquals(50, t.at("/last5/p50").asLong());
        assertEquals(1500, t.at("/last5/p95").asLong());   // 2 of 20 in the 1600 bucket, capped at the largest seen

        JsonNode ep = call(get("/api/apps/shop/endpoints"), admin, null, 200);
        assertEquals(1, ep.size());
        assertEquals(20, ep.get(0).get("requests").asLong());

        JsonNode s = call(get("/api/apps/shop/summary"), admin, null, 200);
        assertEquals("DOWN", s.get("status").asText(), s.toString());   // the health check is failing
        assertTrue(s.get("reasons").get(0).asText().startsWith("Health"), s.toString());

        assertEquals(1, call(get("/api/apps/shop/trace?requestId=abc123"), admin, null, 200).size());
        JsonNode issues = call(get("/api/apps/shop/issues"), admin, null, 200);
        assertEquals(1, issues.at("/totals/ERROR").asLong());
        assertEquals(1, issues.at("/totals/CHECK_FAIL").asLong());
        JsonNode errorsOnly = call(get("/api/apps/shop/issues?type=error"), admin, null, 200);
        assertEquals(1, errorsOnly.get("recent").size());
        assertEquals("ERROR", errorsOnly.at("/groups/0/type").asText());
        call(get("/api/apps/shop/no-such-view"), admin, null, 404);
        assertEquals("mysql", call(get("/api/apps/shop/db"), admin, null, 200).get(0).get("engine").asText());

        // the server's specification is kept as the agent's latest and shown with its host statistics
        ingest(reg3.get("agentId").asText(), reg3.get("agentKey").asText(), Map.of("v", 1, "seq", 2,
                "spec", Map.of("hostname", "web-3", "os", "Ubuntu 24.04 LTS", "cpuModel", "Intel Xeon", "cores", 4, "memTotalMb", 8192)), 200);
        JsonNode hosts = call(get("/api/apps/shop/host"), admin, null, 200);
        JsonNode web3 = null;
        for (JsonNode h : hosts) if (h.get("agentId").asText().equals(reg3.get("agentId").asText())) web3 = h;
        assertNotNull(web3, hosts.toString());
        assertEquals("Ubuntu 24.04 LTS", web3.at("/spec/os").asText());
        assertFalse(web3.get("specAt").isNull());

        // a viewer sees only the apps granted, and cannot administer
        call(post("/api/admin/users"), admin, Map.of("username", "ops", "password", "Viewer@12345", "role", "VIEWER", "apps", List.of("other")), 200);
        String viewer = token("ops", "Viewer@12345");
        JsonNode apps = call(get("/api/apps"), viewer, null, 200);
        assertEquals(1, apps.size());
        assertEquals("other", apps.get(0).get("code").asText());
        call(get("/api/apps/shop/summary"), viewer, null, 404);
        call(post("/api/admin/apps"), viewer, Map.of("code", "x1", "name", "x"), 403);
        mvc.perform(get("/api/apps")).andExpect(status().isUnauthorized());

        // revoked agents are refused
        ingest(id2, key2, Map.of("v", 1, "seq", 1), 401);
        assertTrue(call(get("/api/admin/audit"), admin, null, 200).toString().contains("AGENT_REGISTERED"));
    }

    @Test
    void openTelemetryDependenciesTracesAndAgentOverhead() throws Exception {
        call(post("/api/admin/apps"), admin, Map.of("code", "traced", "name", "Traced app"), 200);
        JsonNode reg = call(post("/api/admin/apps/traced/agents"), admin, Map.of("name", "php-1"), 200);
        String id = reg.get("agentId").asText(), key = reg.get("agentKey").asText();
        long minute = System.currentTimeMillis() / 60000 - 1;
        long[] fast = Hist.empty(); fast[1] = 40;   // 40 calls up to 25 ms
        long[] slow = Hist.empty(); slow[7] = 3;    // 3 calls up to 1600 ms
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        Map<String, Object> batch = Map.of("v", 1, "seq", 1,
                "deps", List.of(
                        Map.of("minute", minute, "kind", "db", "target", "mysql claims @ 127.0.0.1", "operation", "SELECT policies",
                                "calls", 40, "errors", 0, "totalMs", 400, "maxMs", 22, "hist", fast),
                        Map.of("minute", minute, "kind", "http", "target", "api.insurer.example", "operation", "POST /v2/claims/{id}",
                                "calls", 3, "errors", 1, "totalMs", 4200, "maxMs", 1900, "hist", slow)),
                "traces", List.of(Map.of("traceId", trace, "at", System.currentTimeMillis() - 30_000, "name", "POST /claims/{id}/submit",
                        "service", "claims-api", "durationMs", 2300, "error", true, "reason", "error", "status", 502, "requestId", "9f2c1ab07e3d",
                        "spans", List.of(
                                Map.of("id", "00f067aa0ba902b7", "name", "POST /claims/{id}/submit", "kind", 2, "startUs", 0, "durUs", 2_300_000),
                                Map.of("id", "2222222222222222", "parent", "00f067aa0ba902b7", "name", "POST", "kind", 3, "startUs", 100_000,
                                        "durUs", 1_900_000, "error", true, "dep", "http", "target", "api.insurer.example")))),
                "hostStats", Map.of("at", System.currentTimeMillis(), "cpu", 0.25, "memUsedMb", 2000, "memTotalMb", 4000,
                        "agent", Map.of("cpu", 0.004, "rssMb", 96, "heapUsedMb", 30, "heapMaxMb", 128, "spansReceived", 1200)));
        ingest(id, key, batch, 200);
        ingest(id, key, batch, 200);   // the same seq again: ignored, nothing counted twice

        JsonNode deps = call(get("/api/apps/traced/dependencies?minutes=15"), admin, null, 200);
        assertEquals(2, deps.size(), deps.toString());
        assertEquals("http", deps.get(0).get("kind").asText(), "where the time goes first");
        assertEquals(3, deps.get(0).get("calls").asLong());
        assertEquals(1, deps.get(0).get("errors").asLong());
        assertEquals(1400, deps.get(0).get("avg").asLong());
        assertEquals(40, deps.get(1).get("calls").asLong());

        JsonNode traces = call(get("/api/apps/traced/traces?minutes=15"), admin, null, 200);
        assertEquals(1, traces.size());
        assertEquals("error", traces.get(0).get("reason").asText());
        assertEquals(1, call(get("/api/apps/traced/traces?filter=error"), admin, null, 200).size());
        assertEquals(0, call(get("/api/apps/traced/traces?filter=slow"), admin, null, 200).size());
        assertEquals(1, call(get("/api/apps/traced/traces?q=9f2c1ab07e3d"), admin, null, 200).size(), "found by request id");
        assertEquals(1, call(get("/api/apps/traced/traces?q=" + trace), admin, null, 200).size(), "found by trace id");
        assertEquals(1, call(get("/api/apps/traced/traces?q=claims"), admin, null, 200).size(), "found by name");
        JsonNode detail = call(get("/api/apps/traced/traces/" + traces.get(0).get("id").asLong()), admin, null, 200);
        assertEquals(2, detail.get("spans").size());
        assertEquals("api.insurer.example", detail.at("/spans/1/target").asText());
        call(get("/api/apps/traced/traces/999999"), admin, null, 404);

        JsonNode host = call(get("/api/apps/traced/host"), admin, null, 200);
        assertEquals(96, host.at("/0/latest/agent/rssMb").asLong());
        assertEquals(96, host.at("/0/series/0/agentRssMb").asLong());

        // a bad trace id or an unknown kind does not get in as it is
        ingest(id, key, Map.of("v", 1, "seq", 2, "traces", List.of(Map.of("traceId", "not-hex", "spans", List.of())),
                "deps", List.of(Map.of("minute", minute, "kind", "weird", "target", "x", "operation", "", "calls", 1, "errors", 0,
                        "totalMs", 1, "maxMs", 1, "hist", fast))), 200);
        assertEquals(1, call(get("/api/apps/traced/traces"), admin, null, 200).size());
        boolean other = false;
        for (JsonNode d : call(get("/api/apps/traced/dependencies"), admin, null, 200)) other |= d.get("kind").asText().equals("other");
        assertTrue(other);
    }
}
