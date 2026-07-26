package com.anchr.core.ingestion.domain.port;

import com.anchr.core.search.domain.model.EmbeddingProfile;

import java.util.List;

/**
 * Domain port for embedding capability in ingestion.
 */
public interface IngestionEmbeddingPort {

    List<Float> embed(String source, String sourceType);

    boolean isMulti();

    default ServingEmbeddingSession openServingSession() {
        return new ServingEmbeddingSession(
                null, isMulti(), this::embed);
    }

    record ServingEmbeddingSession(
            EmbeddingProfile profile,
            boolean multi,
            Embedder embedder
    ) {
        public List<Float> embed(String source, String sourceType) {
            return embedder.embed(source, sourceType);
        }
    }

    @FunctionalInterface
    interface Embedder {
        List<Float> embed(String source, String sourceType);
    }

}
