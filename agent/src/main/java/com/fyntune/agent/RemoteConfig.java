package com.fyntune.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Settings the agent pulls from the server (GET /ingest/v1/config). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteConfig(Integer slowMs, List<String> mask, List<Map<String, String>> routeRules, List<Map<String, Object>> checks) {}
