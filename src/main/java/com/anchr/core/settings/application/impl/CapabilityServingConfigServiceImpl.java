package com.anchr.core.settings.application.impl;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.settings.application.api.CapabilityServingConfigApi;
import com.anchr.core.settings.application.api.model.CapabilityServingConfigActivation;
import com.anchr.core.settings.domain.model.ModelTypeEnum;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Activates the serving embedding configuration after Retrieval switches aliases. */
@Service
@RequiredArgsConstructor
public class CapabilityServingConfigServiceImpl implements CapabilityServingConfigApi {

    private final CapabilityConfigRepository repository;
    private final ClientCacheManager cacheManager;

    @Override
    @Transactional
    public void activate(CapabilityServingConfigActivation activation) {
        if (activation == null || activation.configId() == null
                || !StringUtils.hasText(activation.capability())) {
            throw new IllegalArgumentException("Embedding activation must reference a config");
        }
        repository.select(activation.capability(), activation.configId());
        if (ModelTypeEnum.EMBEDDING.name().equals(activation.capability())) {
            repository.disableAll(ModelTypeEnum.MULTI_EMBEDDING.name());
        } else if (ModelTypeEnum.MULTI_EMBEDDING.name().equals(activation.capability())) {
            repository.disableAll(ModelTypeEnum.EMBEDDING.name());
        }
        cacheManager.invalidate(CapabilityResolver.SLOT_EMBEDDING);
    }
}
