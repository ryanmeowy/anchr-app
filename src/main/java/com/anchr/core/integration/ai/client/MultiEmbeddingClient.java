package com.anchr.core.integration.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding capability backed by {@link AiClient}.
 */
public class MultiEmbeddingClient implements EmbeddingClient {

    private final AiClient client;

    public MultiEmbeddingClient(String baseUrl, String apiKey) {
        this.client = new AiClient(baseUrl, apiKey);
    }

    @Override
    public EmbeddingResult embed(EmbedContext context) {
        JsonNode root = client.multiEmbeddings(context.modelName(), context.contentMap(), context.extraConfig());
        JsonNode data = root.path("output").path("embeddings");
        if (!data.isArray() || data.isEmpty()) {
            throw new AiClient.OpenAiException(-1, "Empty embedding response.");
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
            Map<String, Object> contentsMap = new HashMap<>();
            contentsMap.put("contents", Lists.newArrayList(map));
            EmbedContext context = EmbedContext.builder().modelName(modelName).contentMap(contentsMap).build();
            EmbeddingResult result = embed(context);
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(true, latency,
                    "连接成功, 向量维度 " + result.dimension(), result.dimension());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(false, latency,
                    "连接失败: " + e.getMessage(), null);
        }
    }
}
