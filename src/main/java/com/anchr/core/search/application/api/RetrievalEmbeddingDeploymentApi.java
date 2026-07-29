package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;

/** Embedding index deployment capability exposed by Retrieval. */
public interface RetrievalEmbeddingDeploymentApi {

    String requestDeployment(RetrievalEmbeddingDeploymentRequest request);
}
