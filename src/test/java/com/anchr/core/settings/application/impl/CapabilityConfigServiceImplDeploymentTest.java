package com.anchr.core.settings.application.impl;

import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.search.domain.repository.EmbeddingDeploymentRepository;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityConfigServiceImplDeploymentTest {

    @Test
    void rollbackPhysicalIndexConfigShouldRemainImmutable() {
        CapabilityConfigRepository configs = mock(CapabilityConfigRepository.class);
        EmbeddingDeploymentRepository deployments = mock(EmbeddingDeploymentRepository.class);
        when(deployments.isConfigProtected(9L)).thenReturn(true);
        CapabilityConfigServiceImpl service = new CapabilityConfigServiceImpl(
                configs, null, null, mock(CapabilityClientFactory.class),
                mock(CapabilityResolver.class), new ClientCacheManager());
        service.setEmbeddingDeploymentRepository(deployments);

        assertThrows(IllegalStateException.class,
                () -> service.del("EMBEDDING", 9L));

        verify(configs, never()).del(any(), any());
    }

    @Test
    void selectingEmbeddingShouldRequestDesiredWithoutReplacingServingConfig() {
        CapabilityConfigRepository configs = mock(CapabilityConfigRepository.class);
        EmbeddingDeploymentRepository deployments = mock(EmbeddingDeploymentRepository.class);
        CapabilityConfig selected = CapabilityConfig.builder()
                .id(22L)
                .capability("MULTI_EMBEDDING")
                .baseUrl("https://embedding.example.test")
                .modelName("multi-v2")
                .extraConfig("{\"dimensions\":1024}")
                .enabled(false)
                .build();
        when(configs.findById(22L)).thenReturn(Optional.of(selected));
        CapabilityConfigServiceImpl service = new CapabilityConfigServiceImpl(
                configs, null, null, mock(CapabilityClientFactory.class),
                mock(CapabilityResolver.class), new ClientCacheManager());
        service.setEmbeddingDeploymentRepository(deployments);

        service.select("MULTI_EMBEDDING", 22L);

        verify(deployments).requestDesired(any());
        verify(configs, never()).select(any(), any());
        verify(configs, never()).disableAll(any());
    }
}
