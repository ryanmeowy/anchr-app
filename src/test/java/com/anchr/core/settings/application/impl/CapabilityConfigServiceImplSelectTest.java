package com.anchr.core.settings.application.impl;

import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.search.application.api.RetrievalEmbeddingDeploymentApi;
import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;
import com.anchr.core.settings.application.acl.CapabilityRetrievalAcl;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityConfigServiceImplSelectTest {

    @Test
    void selectingDifferentEmbeddingProfileShouldRequestRebuildWithoutChangingActiveConfig() {
        CapabilityConfig active = config(1L, "old-model", true);
        CapabilityConfig target = config(2L, "new-model", false);
        RecordingRepository repository = new RecordingRepository(active, target);
        RecordingDeploymentApi deploymentApi = new RecordingDeploymentApi();
        CapabilityConfigServiceImpl service = service(repository, deploymentApi);

        service.select("EMBEDDING", 2L);

        assertNotNull(deploymentApi.request);
        assertEquals(2L, deploymentApi.request.configId());
        assertEquals("new-model", deploymentApi.request.modelName());
        assertEquals(1024, deploymentApi.request.dimension());
        assertFalse(repository.selected);
        assertFalse(repository.disabled);
    }

    @Test
    void sameEmbeddingProfileSelectsImmediatelyWithoutDeployment() {
        CapabilityConfig active = config(1L, "same-model", true);
        CapabilityConfig target = config(2L, "same-model", false);
        RecordingRepository repository = new RecordingRepository(active, target);
        RecordingDeploymentApi deploymentApi = new RecordingDeploymentApi();
        CapabilityConfigServiceImpl service = service(repository, deploymentApi);

        service.select("EMBEDDING", 2L);

        assertTrue(repository.selected);
        assertTrue(repository.disabled);
        assertEquals(null, deploymentApi.request);
    }

    @Test
    void deploymentFailureKeepsExistingServingSelection() {
        CapabilityConfig active = config(1L, "old-model", true);
        CapabilityConfig target = config(2L, "new-model", false);
        RecordingRepository repository = new RecordingRepository(active, target);
        CapabilityConfigServiceImpl service = service(
                repository,
                request -> {
                    throw new IllegalStateException("deployment unavailable");
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.select("EMBEDDING", 2L));

        assertEquals("deployment unavailable", failure.getMessage());
        assertFalse(repository.selected);
        assertFalse(repository.disabled);
    }

    private CapabilityConfigServiceImpl service(
            RecordingRepository repository,
            RetrievalEmbeddingDeploymentApi deploymentApi
    ) {
        CapabilityClientFactory clientFactory = new CapabilityClientFactory(null) {
            @Override
            public Object build(CapabilityConfig config) {
                return new Object();
            }
        };
        return new CapabilityConfigServiceImpl(
                repository,
                null,
                null,
                clientFactory,
                new CapabilityResolver(repository),
                new ClientCacheManager(),
                new CapabilityRetrievalAcl(deploymentApi));
    }

    private CapabilityConfig config(Long id, String modelName, boolean enabled) {
        return CapabilityConfig.builder()
                .id(id)
                .capability("EMBEDDING")
                .baseUrl("https://embedding.example.test")
                .modelName(modelName)
                .extraConfig("{\"dimensions\":1024}")
                .enabled(enabled)
                .build();
    }

    private static final class RecordingRepository implements CapabilityConfigRepository {
        private final CapabilityConfig active;
        private final CapabilityConfig target;
        private boolean selected;
        private boolean disabled;

        private RecordingRepository(CapabilityConfig active, CapabilityConfig target) {
            this.active = active;
            this.target = target;
        }

        @Override
        public List<CapabilityConfig> findByCapability(String capability) {
            return "EMBEDDING".equals(capability) ? List.of(active) : List.of();
        }

        @Override
        public List<CapabilityConfig> findAllByCapability(String capability) {
            return findByCapability(capability);
        }

        @Override
        public Optional<CapabilityConfig> findById(Long id) {
            return target.getId().equals(id) ? Optional.of(target) : Optional.empty();
        }

        @Override
        public CapabilityConfig insert(CapabilityConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CapabilityConfig update(CapabilityConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void select(String capability, Long id) {
            selected = true;
        }

        @Override
        public void disableAll(String capability) {
            disabled = true;
        }

        @Override
        public void del(String capability, Long id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingDeploymentApi implements RetrievalEmbeddingDeploymentApi {
        private RetrievalEmbeddingDeploymentRequest request;

        @Override
        public String requestDeployment(RetrievalEmbeddingDeploymentRequest request) {
            this.request = request;
            return "task-1";
        }
    }
}
