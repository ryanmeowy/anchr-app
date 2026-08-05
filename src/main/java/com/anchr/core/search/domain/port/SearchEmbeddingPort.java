package com.anchr.core.search.domain.port;

import com.anchr.core.search.domain.model.EmbeddingProfile;

import java.util.List;

/**
 * Domain port for embedding capabilities used by search.
 */
public interface SearchEmbeddingPort {

    List<Float> embed(String source, String sourceType);

    default EmbeddingSession openSession(EmbeddingProfile profile) {
        return SearchEmbeddingPort.this::embed;
    }

    interface EmbeddingSession {
        List<Float> embed(String source, String sourceType);

        default List<List<Float>> embedBatch(List<EmbeddingInput> inputs) {
            return inputs.stream()
                    .map(input -> embed(input.source(), input.sourceType()))
                    .toList();
        }
    }

    record EmbeddingInput(String source, String sourceType) {
    }
}
