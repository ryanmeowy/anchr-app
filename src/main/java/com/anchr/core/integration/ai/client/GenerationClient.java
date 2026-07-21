package com.anchr.core.integration.ai.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Generation capability backed by {@link AiClient}.
 */
public class GenerationClient {

    private final AiClient client;

    public GenerationClient(String baseUrl, String apiKey) {
        this.client = new AiClient(baseUrl, apiKey);
    }

    /**
     * Generate text from a list of messages.
     */
    public GenerationResult generate(String modelName, List<Map<String, String>> messages,
                                     Map<String, Object> extraConfig) {
        return generate(modelName, messages, extraConfig, Duration.ofSeconds(30));
    }

    public GenerationResult generate(String modelName, List<Map<String, String>> messages,
                                     Map<String, Object> extraConfig, Duration timeout) {
        JsonNode root = client.chatCompletions(modelName, messages, extraConfig, timeout);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiClient.OpenAiException(-1, "Empty generation response.");
        }
        String content = choices.get(0).path("message").path("content").asText();
        JsonNode usage = root.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt();
        int completionTokens = usage.path("completion_tokens").asInt();
        return new GenerationResult(content, promptTokens, completionTokens);
    }

    public GenerationResult generateStream(String modelName,
                                           List<Map<String, String>> messages,
                                           Map<String, Object> extraConfig,
                                           Duration timeout,
                                           Consumer<String> onDelta) {
        AiClient.StreamedChatCompletion result = client.chatCompletionsStream(
                modelName, messages, extraConfig, timeout, onDelta);
        return new GenerationResult(
                result.content(), result.promptTokens(), result.completionTokens());
    }

    /**
     * Simple single-turn generation with a user prompt.
     */
    public GenerationResult generate(String modelName, Map<String, Object> extraConfig, String userMessage) {
        return generate(modelName,
                List.of(Map.of("role", "user", "content", userMessage)),
                extraConfig);
    }

    /**
     * Test connection with a minimal prompt.
     */
    public ConnectionTestResult testConnection(String modelName) {
        long start = System.currentTimeMillis();
        try {
            GenerationResult result = generate(modelName, null, "hi");
            long latency = System.currentTimeMillis() - start;
            String preview = result.content();
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            return new ConnectionTestResult(true, latency,
                    "连接成功: " + preview, result.promptTokens());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(false, latency,
                    "连接失败: " + e.getMessage(), 0);
        }
    }

    public record GenerationResult(String content, int promptTokens, int completionTokens) {}

    public record ConnectionTestResult(boolean success, long latencyMs, String message, int promptTokens) {}
}
