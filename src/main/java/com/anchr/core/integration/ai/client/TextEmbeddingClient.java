package com.anchr.core.integration.ai.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding capability backed by {@link AiClient}.
 */
public class TextEmbeddingClient implements EmbeddingClient {

    private final AiClient client;

    public TextEmbeddingClient(String baseUrl, String apiKey) {
        this.client = new AiClient(baseUrl, apiKey);
    }

    @Override
    public EmbeddingResult embed(EmbedContext context) {
        JsonNode root = client.embeddings(context.modelName(), context.texts(), context.extraConfig());
        JsonNode data = root.path("data");
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
            EmbedContext context = EmbedContext.builder().modelName(modelName).texts(List.of("test")).build();
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
