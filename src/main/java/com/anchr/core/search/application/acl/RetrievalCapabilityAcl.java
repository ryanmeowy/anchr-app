package com.anchr.core.search.application.acl;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.settings.application.api.CapabilityServingConfigApi;
import com.anchr.core.settings.application.api.model.CapabilityServingConfigActivation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Retrieval-side adapter for activating a serving Capability configuration. */
@Component
@RequiredArgsConstructor
public class RetrievalCapabilityAcl {

    private final CapabilityServingConfigApi capabilityServingConfigApi;

    public void activateServingProfile(EmbeddingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding profile is required");
        }
        capabilityServingConfigApi.activate(
                new CapabilityServingConfigActivation(
                        profile.configId(), profile.capability()));
    }
}
