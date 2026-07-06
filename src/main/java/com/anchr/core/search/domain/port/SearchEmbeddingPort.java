package com.anchr.core.search.domain.port;

import com.anchr.core.search.domain.model.EmbeddingProfile;

import java.util.List;

/**
 * Domain port for embedding capabilities used by search.
 */
public interface SearchEmbeddingPort {

    List<Float> embed(String source, String sourceType);

    default EmbeddingSession openSession(EmbeddingProfile profile) {
        return this::embed;
    }

    @FunctionalInterface
    interface EmbeddingSession {
        List<Float> embed(String source, String sourceType);
    }
}
