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
        return embedMany(context).getFirst();
    }

    @Override
    public List<EmbeddingResult> embedMany(EmbedContext context) {
        JsonNode root = client.embeddings(context.modelName(), context.texts(), context.extraConfig());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new AiClient.OpenAiException(-1, "Empty embedding response.");
        }
        List<EmbeddingResult> results = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode rawEmbedding = item.path("embedding");
            List<Float> vector = new ArrayList<>();
            for (JsonNode val : rawEmbedding) {
                vector.add((float) val.asDouble());
            }
            results.add(new EmbeddingResult(vector, vector.size()));
        }
        return results;
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
                    "Connection successful, vector dimension" + result.dimension(), result.dimension());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(false, latency,
                    "Connection failed: " + e.getMessage(), null);
        }
    }

}
