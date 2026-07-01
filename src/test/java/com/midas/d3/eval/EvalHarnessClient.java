package com.midas.d3.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin, dependency-light REST client over the MIDAS pipeline + dashboard APIs. Test-scope only.
 *
 * <p>Deliberately provider-agnostic: it drives whatever backend is listening at {@code baseUrl}
 * (Docker :8080 or the native jar :8081), through the exact same door a paying customer uses. The
 * model under test is chosen at runtime <em>on that backend</em> ({@code MIDAS_LLM_MODEL*}), so this
 * harness hardcodes no cost or provider.
 */
public final class EvalHarnessClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public EvalHarnessClient(String baseUrl, String token, ObjectMapper mapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.token = token;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Starts an auto-mode run and returns its {@code runId}. */
    public String start(String rawUserIdea) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rawUserIdea", rawUserIdea);
        body.put("autoMode", true);
        HttpResponse<String> resp = send("POST", "/api/v1/pipelines", mapper.writeValueAsString(body));
        requireStatus(resp, 201, 200);
        return mapper.readTree(resp.body()).path("runId").asText();
    }

    /** Current public pipeline state (e.g. {@code CODE_GENERATION}, {@code COMPLETED}, {@code ERROR}). */
    public String status(String runId) throws Exception {
        HttpResponse<String> resp = send("GET", "/api/v1/pipelines/" + runId + "/status", null);
        requireStatus(resp, 200);
        return mapper.readTree(resp.body()).path("state").asText();
    }

    /** Full context snapshot (qualityScore, productReviewReport, auditLog, lastErrorMessage, …). */
    public JsonNode context(String runId) throws Exception {
        HttpResponse<String> resp = send("GET", "/api/v1/pipelines/" + runId + "/context", null);
        requireStatus(resp, 200);
        return mapper.readTree(resp.body());
    }

    /**
     * Dashboard run detail (aggregated tokens, cost, per-agent model/timing). Best-effort: returns
     * {@code null} rather than throwing, because it is only persisted once the run is recorded and is
     * economics-only — a missing dashboard row must not fail the eval measurement itself.
     */
    public JsonNode dashboardRun(String runId) {
        try {
            HttpResponse<String> resp = send("GET", "/api/v1/dashboard/runs/" + runId, null);
            if (resp.statusCode() != 200) return null;
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void requireStatus(HttpResponse<String> resp, int... acceptable) {
        for (int s : acceptable) {
            if (resp.statusCode() == s) return;
        }
        throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + resp.uri()
                + " → " + truncate(resp.body(), 300));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
