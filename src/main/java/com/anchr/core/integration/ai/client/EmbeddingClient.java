package com.anchr.core.integration.ai.client;

import lombok.Builder;

import java.util.List;
import java.util.Map;

public interface EmbeddingClient {

    EmbeddingResult embed(EmbedContext context);

    List<EmbeddingResult> embedMany(EmbedContext context);

    ConnectionTestResult testConnection(String modelName);

    record EmbeddingResult(List<Float> vector, int dimension) {}

    record ConnectionTestResult(boolean success, long latencyMs, String message, Integer dimension) {}

    @Builder
    record EmbedContext(String modelName, Map<String, Object> extraConfig, Map<String, Object> contentMap, List<String> texts){}
}
