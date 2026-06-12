package com.anchr.core.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding capability backed by {@link OpenAiClient}.
 */
public class EmbeddingClient {

    private final OpenAiClient client;

    public EmbeddingClient(String baseUrl, String apiKey) {
        this.client = new OpenAiClient(baseUrl, apiKey);
    }

    /**
     * Embed a single text.
     */
    public EmbeddingResult embedText(String modelName, Map<String, Object> extraConfig, String text) {
        return embedTexts(modelName, extraConfig, List.of(text));
    }

    /**
     * Embed multiple texts in one request.
     */
    public EmbeddingResult embedTexts(String modelName, Map<String, Object> extraConfig, List<String> texts) {
        JsonNode root = client.embeddings(modelName, texts, extraConfig);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new OpenAiClient.OpenAiException(-1, "Empty embedding response.");
        }
        JsonNode firstEmbedding = data.get(0).path("embedding");
        List<Float> vector = new ArrayList<>();
        for (JsonNode val : firstEmbedding) {
            vector.add((float) val.asDouble());
        }
        return new EmbeddingResult(vector, vector.size());
    }

    /**
     * Test connection by embedding a short text.
     */
    public ConnectionTestResult testConnection(String modelName) {
        long start = System.currentTimeMillis();
        try {
            EmbeddingResult result = embedText(modelName, null, "test");
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(true, latency,
                    "连接成功, 向量维度 " + result.dimension(), result.dimension());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(false, latency,
                    "连接失败: " + e.getMessage(), null);
        }
    }

    public record EmbeddingResult(List<Float> vector, int dimension) {}

    public record ConnectionTestResult(boolean success, long latencyMs, String message, Integer dimension) {}

}
