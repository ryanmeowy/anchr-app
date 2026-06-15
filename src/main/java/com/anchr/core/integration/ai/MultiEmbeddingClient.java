package com.anchr.core.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding capability backed by {@link OpenAiClient}.
 */
public class MultiEmbeddingClient {

    private final OpenAiClient client;

    public MultiEmbeddingClient(String baseUrl, String apiKey) {
        this.client = new OpenAiClient(baseUrl, apiKey);
    }

    public EmbeddingResult multiEmbed(String modelName, Map<String, Object> extraConfig, Map<String, Object> contents) {
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("contents", Lists.newArrayList(contents));
        return embed(modelName, extraConfig, inputMap);
    }

    /**
     * Embed multiple texts in one request.
     */
    public EmbeddingResult embed(String modelName, Map<String, Object> extraConfig, Map<String, Object> input) {
        JsonNode root = client.multiEmbeddings(modelName, input, extraConfig);
        JsonNode data = root.path("output").path("embeddings");
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
            Map<String, Object> map = new HashMap<>();
            map.put("text", "测试");
            EmbeddingResult result = multiEmbed(modelName, null, map);
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
