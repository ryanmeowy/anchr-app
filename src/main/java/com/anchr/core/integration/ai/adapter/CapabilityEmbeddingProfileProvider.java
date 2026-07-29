package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.settings.application.support.CapabilityEmbeddingProfileFactory;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CapabilityEmbeddingProfileProvider implements EmbeddingProfileProvider {

    private final CapabilityResolver configResolver;

    @Override
    public Optional<EmbeddingProfile> getActiveEmbeddingProfile() {
        return configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .flatMap(CapabilityEmbeddingProfileProvider::createProfile);
    }

    public static Optional<EmbeddingProfile> createProfile(CapabilityConfig config) {
        return CapabilityEmbeddingProfileFactory.create(config)
                .map(profile -> new EmbeddingProfile(
                        profile.configId(),
                        profile.capability(),
                        profile.modelName(),
                        profile.dimension(),
                        profile.fingerprint()));
    }
}
