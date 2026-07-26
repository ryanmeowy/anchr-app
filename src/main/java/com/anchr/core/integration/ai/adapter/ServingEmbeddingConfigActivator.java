package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.settings.domain.model.ModelTypeEnum;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Activates the selected embedding configuration after index rebuild and alias switch. */
@Component
@RequiredArgsConstructor
public class ServingEmbeddingConfigActivator {

    private final CapabilityConfigRepository repository;
    private final ClientCacheManager cacheManager;

    @Transactional
    public void activate(EmbeddingProfile profile) {
        if (profile == null || profile.configId() == null) {
            throw new IllegalArgumentException("Embedding profile must reference a config");
        }
        repository.select(profile.capability(), profile.configId());
        if (ModelTypeEnum.EMBEDDING.name().equals(profile.capability())) {
            repository.disableAll(ModelTypeEnum.MULTI_EMBEDDING.name());
        } else if (ModelTypeEnum.MULTI_EMBEDDING.name().equals(profile.capability())) {
            repository.disableAll(ModelTypeEnum.EMBEDDING.name());
        }
        cacheManager.invalidate(CapabilityResolver.SLOT_EMBEDDING);
    }
}
