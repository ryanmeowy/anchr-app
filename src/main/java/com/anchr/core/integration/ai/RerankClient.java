package com.anchr.core.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank capability backed by {@link OpenAiClient}.
 */
public class RerankClient {

    private final OpenAiClient client;

    public RerankClient(String baseUrl, String apiKey) {
        this.client = new OpenAiClient(baseUrl, apiKey);
    }

    /**
     * Rerank documents against a query.
     */
    public RerankResult rerank(String modelName, String query, List<String> documents,
                               Map<String, Object> extraConfig) {
        JsonNode root = client.rerank(modelName, query, documents, extraConfig);
        JsonNode results = root.path("results");
        List<RerankItem> items = new ArrayList<>();
        for (JsonNode item : results) {
            items.add(new RerankItem(
                    item.path("index").asInt(),
                    item.path("relevance_score").asDouble(),
                    item.has("document") ? item.path("document").path("text").asText() : null));
        }
        return new RerankResult(items);
    }

    /**
     * Test connection with a minimal rerank call.
     */
    public ConnectionTestResult testConnection(String modelName) {
        long start = System.currentTimeMillis();
        try {
            RerankResult result = rerank(modelName, "test", List.of("test document"), null);
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(true, latency,
                    "连接成功, 返回 " + result.items().size() + " 条结果");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(false, latency,
                    "连接失败: " + e.getMessage());
        }
    }

    public record RerankItem(int index, double relevanceScore, String document) {}

    public record RerankResult(List<RerankItem> items) {}

    public record ConnectionTestResult(boolean success, long latencyMs, String message) {}
}
