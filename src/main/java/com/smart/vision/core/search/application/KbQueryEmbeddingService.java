package com.smart.vision.core.search.application;

import java.util.List;

/**
 * Generates embedding vectors for kb search queries.
 */
public interface KbQueryEmbeddingService {

    /**
     * Build query embedding vector from user query text.
     *
     * @param query user query
     * @return embedding vector
     */
    List<Float> embedQuery(String query);
}
