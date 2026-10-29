package com.fyntune.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fyntune.monitor.alerts.Alert;
import com.fyntune.monitor.alerts.AlertSender;
import com.fyntune.monitor.alerts.Alerter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Status changes become one message each: Down, still Down, recovered; paused apps stay quiet. Nothing leaves the test. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertFlowTest {
    record Sent(String kind, String target, Alert alert) {}

    static final List<Sent> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Capture {
        @Bean @Primary
        AlertSender captureSender() {
            return new AlertSender() {
                public void send(String kind, String target, Alert a) { SENT.add(new Sent(kind, target, a)); }
                public boolean whatsappReady() { return false; }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired Alerter alerter;
    @Autowired com.fyntune.monitor.auth.AdminSeeder seeder;

    String admin, agentId, agentKey;
    long seq;
    static final String HOOK = "https://hooks.slack.com/services/T000/B000/abcdefghijklmnop";

    @BeforeAll
    void fresh() throws Exception {
        flyway.clean();
        flyway.migrate();
        seeder.run(null);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "admin", "password", "Password@123")))).andReturn().getResponse().getContentAsString();
        admin = json.readTree(body).get("token").asText();
    }

    JsonNode call(MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        r.header("Authorization", "Bearer " + admin);
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String s = mvc.perform(r).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    void health(boolean ok) throws Exception {
        mvc.perform(post("/ingest/v1/batch").header("X-Agent-Id", agentId).header("X-Agent-Key", agentKey).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("v", 1, "seq", ++seq, "checks",
                        List.of(Map.of("name", "Health", "kind", "HTTP", "ok", ok, "ms", 20, "message", ok ? "HTTP 200" : "HTTP 503 (expected 200)"))))))
                .andExpect(status().isOk());
    }

    @Test
    void alerts() throws Exception {
        call(post("/api/admin/apps"), Map.of("code", "pay", "name", "Payments", "environment", "production"), 200);
        call(post("/api/admin/apps/pay/checks"), Map.of("name", "Health", "kind", "HTTP", "health", true, "config", Map.of("url", "https://pay.example.com/health")), 200);
        JsonNode reg = call(post("/api/admin/apps/pay/agents"), Map.of("name", "web-1"), 200);
        agentId = reg.get("agentId").asText();
        agentKey = reg.get("agentKey").asText();

        // channels: only Slack's own webhook host; WhatsApp refused while the server has no credentials
        call(post("/api/admin/apps/pay/alerts"), Map.of("kind", "SLACK", "target", "https://evil.example.com/hook"), 400);
        call(post("/api/admin/apps/pay/alerts"), Map.of("kind", "WHATSAPP", "target", "+91 98765 43210"), 400);
        long ch = call(post("/api/admin/apps/pay/alerts"), Map.of("kind", "SLACK", "target", HOOK, "label", "#ops"), 200).get("id").asLong();
        JsonNode list = call(get("/api/admin/apps/pay/alerts"), null, 200);
        assertFalse(list.toString().contains("abcdefghijklmnop"), "the webhook is a secret: " + list);

        health(true);
        alerter.run();
        assertTrue(SENT.isEmpty(), "Up from the start: nothing to say");

        health(false);
        alerter.run();
        assertEquals(1, SENT.size());
        assertEquals("SLACK", SENT.get(0).kind());
        assertEquals(HOOK, SENT.get(0).target());
        assertEquals("Payments (production) is Down", SENT.get(0).alert().headline());
        assertEquals("https://monitor.example.com/apps/pay", SENT.get(0).alert().link());
        alerter.run();
        assertEquals(1, SENT.size(), "the same status is not sent twice");

        // still Down after the repeat interval
        jdbc.update("UPDATE alert_state SET last_sent_at = now() - interval '31 minutes'");
        alerter.run();
        assertEquals(2, SENT.size());
        assertEquals("REMINDER", SENT.get(1).alert().kind());

        health(true);
        alerter.run();
        assertEquals(3, SENT.size());
        assertEquals("RECOVERED", SENT.get(2).alert().kind());
        assertTrue(SENT.get(2).alert().headline().startsWith("Payments (production) is Up again"), SENT.get(2).alert().headline());

        // paused: the state moves on, but nothing is sent, now or after the pause
        call(put("/api/admin/apps/pay/alerts/mute"), Map.of("minutes", 60), 200);
        health(false);
        alerter.run();
        assertEquals(3, SENT.size());
        call(put("/api/admin/apps/pay/alerts/mute"), Map.of("minutes", 0), 200);
        alerter.run();
        assertEquals(3, SENT.size(), "the Down that happened while paused is not sent late");

        call(post("/api/admin/apps/pay/alerts/" + ch + "/test"), null, 200);
        assertEquals("TEST", SENT.get(3).alert().kind());

        call(patch("/api/admin/apps/pay/alerts/" + ch), Map.of("includeDegraded", false), 200);
        list = call(get("/api/admin/apps/pay/alerts"), null, 200);
        assertFalse(list.at("/channels/0/includeDegraded").asBoolean());
        assertEquals(4, list.get("recent").size(), "Down, reminder, recovered and the test are logged: " + list);
    }
}
