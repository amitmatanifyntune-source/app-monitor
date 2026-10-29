package com.fyntune.monitor.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The two message formats, without sending anything. */
class HttpAlertSenderTest {
    final HttpAlertSender sender = new HttpAlertSender(new ObjectMapper(), "token", "123", "app_monitor_alert", "en", "https://graph.facebook.com/v22.0/");
    final Alert down = new Alert("CHANGE", "Shop <Laravel>", "uat", "shop", "DOWN", "UP",
            List.of("Health: HTTP 503\n(expected 200)", "2 server errors in the last 15 minutes"), 0, "https://monitor.example.com/apps/shop");

    @Test
    void slackEscapesAndLinks() {
        String text = (String) sender.slack(down).get("text");
        assertTrue(text.startsWith(":red_circle: *Shop &lt;Laravel&gt; (uat) is Down*"), text);
        assertTrue(text.contains("\n• 2 server errors"), text);
        assertTrue(text.endsWith("<https://monitor.example.com/apps/shop|Open in App Monitor>"), text);
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatsappUsesTheTemplateWithThreeCleanValues() {
        Map<String, Object> body = sender.whatsapp("919876543210", down);
        assertEquals("919876543210", body.get("to"));
        Map<String, Object> t = (Map<String, Object>) body.get("template");
        assertEquals("app_monitor_alert", t.get("name"));
        List<Map<String, String>> params = (List<Map<String, String>>) ((List<Map<String, Object>>) t.get("components")).get(0).get("parameters");
        assertEquals(3, params.size());
        assertEquals("Shop <Laravel> (uat) is Down", params.get(0).get("text"));
        assertFalse(params.get(1).get("text").contains("\n"), "WhatsApp refuses new lines in template values");
        assertEquals("https://monitor.example.com/apps/shop", params.get(2).get("text"));
    }

    @Test
    void headlines() {
        assertEquals("Pay (production) is Up again, after 2 h 5 min Down",
                new Alert("RECOVERED", "Pay", "production", "pay", "UP", "DOWN", List.of(), 125, null).headline());
        assertEquals("Pay (production) is Degraded, no longer Down",
                new Alert("CHANGE", "Pay", "production", "pay", "DEGRADED", "DOWN", List.of(), 3, null).headline());
    }
}
