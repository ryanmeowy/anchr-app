package com.anchr.core.settings.application.impl;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.settings.application.api.model.CapabilityServingConfigActivation;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CapabilityServingConfigServiceImplTest {

    private final CapabilityConfigRepository repository = mock(CapabilityConfigRepository.class);
    private final ClientCacheManager cacheManager = mock(ClientCacheManager.class);
    private final CapabilityServingConfigServiceImpl service =
            new CapabilityServingConfigServiceImpl(repository, cacheManager);

    @Test
    void activatesTextEmbeddingDisablesMultimodalAndInvalidatesServingCache() {
        service.activate(new CapabilityServingConfigActivation(42L, "EMBEDDING"));

        var ordered = inOrder(repository, cacheManager);
        ordered.verify(repository).select("EMBEDDING", 42L);
        ordered.verify(repository).disableAll("MULTI_EMBEDDING");
        ordered.verify(cacheManager).invalidate(CapabilityResolver.SLOT_EMBEDDING);
    }

    @Test
    void activatesMultimodalEmbeddingAndDisablesTextEmbedding() {
        service.activate(new CapabilityServingConfigActivation(43L, "MULTI_EMBEDDING"));

        verify(repository).select("MULTI_EMBEDDING", 43L);
        verify(repository).disableAll("EMBEDDING");
        verify(cacheManager).invalidate(CapabilityResolver.SLOT_EMBEDDING);
    }

    @Test
    void rejectsCommandWithoutConfigBeforeMutatingServingState() {
        assertThatThrownBy(() -> service.activate(
                new CapabilityServingConfigActivation(null, "EMBEDDING")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).select(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(cacheManager, never()).invalidate(org.mockito.ArgumentMatchers.any());
    }
}
