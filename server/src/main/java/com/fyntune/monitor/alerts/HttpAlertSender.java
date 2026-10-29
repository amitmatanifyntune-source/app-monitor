package com.fyntune.monitor.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Slack: an incoming webhook (https://api.slack.com/messaging/webhooks). WhatsApp: the Business Cloud API, with an
 * approved template whose body has three variables: {{1}} the headline, {{2}} the details, {{3}} the link.
 */
@Component
public class HttpAlertSender implements AlertSender {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String waToken, waPhoneId, waTemplate, waLanguage, waApi;

    public HttpAlertSender(ObjectMapper json, @Value("${app.alerts.whatsapp.token}") String waToken,
                           @Value("${app.alerts.whatsapp.phone-number-id}") String waPhoneId, @Value("${app.alerts.whatsapp.template}") String waTemplate,
                           @Value("${app.alerts.whatsapp.language}") String waLanguage, @Value("${app.alerts.whatsapp.api}") String waApi) {
        this.json = json;
        this.waToken = waToken;
        this.waPhoneId = waPhoneId;
        this.waTemplate = waTemplate;
        this.waLanguage = waLanguage;
        this.waApi = waApi.replaceAll("/+$", "");
    }

    @Override
    public boolean whatsappReady() { return !waToken.isBlank() && !waPhoneId.isBlank() && !waTemplate.isBlank(); }

    @Override
    public void send(String kind, String target, Alert a) throws Exception {
        if ("SLACK".equals(kind)) post(target, null, slack(a), "Slack");
        else if ("WHATSAPP".equals(kind)) {
            if (!whatsappReady()) throw new IllegalStateException("WhatsApp is not set up on this server (WHATSAPP_TOKEN, WHATSAPP_PHONE_NUMBER_ID)");
            post(waApi + "/" + waPhoneId + "/messages", waToken, whatsapp(target, a), "WhatsApp");
        } else throw new IllegalArgumentException("Unknown channel " + kind);
    }

    Map<String, Object> slack(Alert a) {
        String icon = switch (a.kind()) {
            case "RECOVERED" -> ":large_green_circle:";
            case "TEST" -> ":white_check_mark:";
            default -> "DOWN".equals(a.status()) ? ":red_circle:" : ":large_orange_circle:";
        };
        StringBuilder text = new StringBuilder(icon).append(" *").append(esc(a.headline())).append('*');
        if (a.reasons() != null) a.reasons().stream().limit(5).forEach(r -> text.append("\n• ").append(esc(r)));
        if (a.link() != null) text.append("\n<").append(a.link()).append("|Open in App Monitor>");
        return Map.of("text", text.toString());
    }

    Map<String, Object> whatsapp(String to, Alert a) {
        List<Map<String, String>> params = new ArrayList<>();
        for (String p : List.of(a.headline(), a.details().isEmpty() ? "No further detail" : a.details(), a.link() == null ? "-" : a.link()))
            params.add(Map.of("type", "text", "text", param(p)));
        return Map.of("messaging_product", "whatsapp", "to", to, "type", "template",
                "template", Map.of("name", waTemplate, "language", Map.of("code", waLanguage), "components", List.of(Map.of("type", "body", "parameters", params))));
    }

    private void post(String url, String bearer, Object body, String service) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String b = res.body() == null ? "" : res.body().replaceAll("\\s+", " ").trim();
            throw new IllegalStateException(service + " answered " + res.statusCode() + (b.isEmpty() ? "" : ": " + (b.length() > 300 ? b.substring(0, 300) : b)));
        }
    }

    /** Slack's three control characters. */
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** WhatsApp refuses template values with new lines, tabs or more than four spaces in a row, and caps their length. */
    static String param(String s) {
        String p = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {4,}", "   ").trim();
        return p.length() > 900 ? p.substring(0, 897) + "…" : p;
    }
}
