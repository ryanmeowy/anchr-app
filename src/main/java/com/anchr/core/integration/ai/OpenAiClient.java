package com.anchr.core.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight OpenAI-compatible HTTP client shared by embedding, generation, and rerank.
 */
@Slf4j
public class OpenAiClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String apiKey;

    public OpenAiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
    }

    // ── low-level POST ──────────────────────────────────────────────────

    public JsonNode post(String path, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorBody = response.body();
                if (errorBody != null && errorBody.length() > 200) {
                    errorBody = errorBody.substring(0, 200);
                }
                throw new OpenAiException(response.statusCode(), errorBody);
            }

            return objectMapper.readTree(response.body());
        } catch (OpenAiException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenAiException(-1, e.getMessage());
        }
    }

    // ── capability shortcuts ────────────────────────────────────────────

    /** POST /embeddings */
    public JsonNode embeddings(String model, List<String> input, Map<String, Object> extraConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("/embeddings", body);
    }

    /** POST /chat/completions */
    public JsonNode chatCompletions(String model, List<Map<String, String>> messages,
                                    Map<String, Object> extraConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("/chat/completions", body);
    }

    /** POST /rerank */
    public JsonNode rerank(String model, String query, List<String> documents,
                           Map<String, Object> extraConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("/rerank", body);
    }

    // ── error type ──────────────────────────────────────────────────────

    public static class OpenAiException extends RuntimeException {
        private final int statusCode;

        public OpenAiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() { return statusCode; }
    }
}
