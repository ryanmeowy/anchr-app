package com.anchr.core.settings.application.acl;

import com.anchr.core.search.application.api.RetrievalEmbeddingDeploymentApi;
import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;
import com.anchr.core.settings.application.model.CapabilityEmbeddingProfileSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Capability-side adapter for Retrieval embedding deployments. */
@Component
@RequiredArgsConstructor
public class CapabilityRetrievalAcl {

    private final RetrievalEmbeddingDeploymentApi retrievalEmbeddingDeploymentApi;

    public String requestDeployment(CapabilityEmbeddingProfileSnapshot profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding profile is required");
        }
        return retrievalEmbeddingDeploymentApi.requestDeployment(
                new RetrievalEmbeddingDeploymentRequest(
                        profile.configId(),
                        profile.capability(),
                        profile.modelName(),
                        profile.dimension(),
                        profile.fingerprint()));
    }
}
