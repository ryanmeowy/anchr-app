package com.anchr.core.search.application.api.model;

/** Immutable embedding profile required by a Retrieval index deployment. */
public record RetrievalEmbeddingDeploymentRequest(
        Long configId,
        String capability,
        String modelName,
        int dimension,
        String fingerprint
) {
}
