package com.anchr.core.search.domain.model;

/**
 * Immutable identity of an embedding configuration relevant to indexed vectors.
 */
public record EmbeddingProfile(
        Long configId,
        String capability,
        String modelName,
        int dimension,
        String fingerprint
) {
    public EmbeddingProfile {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("Embedding capability is required");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Embedding model name is required");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Embedding profile fingerprint is required");
        }
    }
}
