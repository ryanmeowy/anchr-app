package com.anchr.core.integration.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lightweight OpenAI-compatible HTTP client shared by embedding, generation, and rerank.
 */
@Slf4j
public class AiClient {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String apiKey;

    public AiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
    }

    // ── low-level POST ──────────────────────────────────────────────────

    public JsonNode post(String path, Map<String, Object> body) {
        return post(path, body, DEFAULT_REQUEST_TIMEOUT);
    }

    public JsonNode post(String path, Map<String, Object> body, Duration timeout) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(timeout == null ? DEFAULT_REQUEST_TIMEOUT : timeout)
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
        return chatCompletions(model, messages, extraConfig, DEFAULT_REQUEST_TIMEOUT);
    }

    public JsonNode chatCompletions(String model, List<Map<String, String>> messages,
                                    Map<String, Object> extraConfig, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("/chat/completions", body, timeout);
    }

    /** POST /chat/completions using the OpenAI-compatible SSE protocol. */
    public StreamedChatCompletion chatCompletionsStream(String model,
                                                        List<Map<String, String>> messages,
                                                        Map<String, Object> extraConfig,
                                                        Duration timeout,
                                                        Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (extraConfig != null) body.putAll(extraConfig);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(timeout == null ? DEFAULT_REQUEST_TIMEOUT : timeout)
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream errorStream = response.body()) {
                    String error = new String(errorStream.readNBytes(200), StandardCharsets.UTF_8);
                    throw new OpenAiException(response.statusCode(), error);
                }
            }

            StringBuilder content = new StringBuilder();
            int promptTokens = 0;
            int completionTokens = 0;
            try (InputStream input = response.body();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("streaming request interrupted");
                    }
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    JsonNode event = objectMapper.readTree(data);
                    JsonNode choices = event.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                        JsonNode value = choices.get(0).path("delta").path("content");
                        if (value.isTextual() && !value.asText().isEmpty()) {
                            String delta = value.asText();
                            content.append(delta);
                            if (onDelta != null) onDelta.accept(delta);
                        }
                    }
                    JsonNode usage = event.path("usage");
                    if (usage.isObject()) {
                        promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                        completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    }
                }
            }
            return new StreamedChatCompletion(content.toString(), promptTokens, completionTokens);
        } catch (OpenAiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OpenAiException(-1, e.getMessage());
        }
    }

    /** only for aliyun */
    public JsonNode rerank(String model, String query, List<String> documents,
                           Map<String, Object> extraConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("", body);
    }

    /** only for aliyun */
    public JsonNode multiEmbeddings(String model, Map<String, Object> input, Map<String, Object> extraConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        if (extraConfig != null) {
            body.putAll(extraConfig);
        }
        return post("", body);
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

    public record StreamedChatCompletion(String content, int promptTokens, int completionTokens) {
    }
}
