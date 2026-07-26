package com.anchr.core.settings.application.impl;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CapabilityConfigServiceImplSelectTest {

    @Test
    void selectingDifferentEmbeddingProfileShouldRequestRebuildWithoutChangingActiveConfig() {
        CapabilityConfig active = config(1L, "old-model", true);
        CapabilityConfig target = config(2L, "new-model", false);
        RecordingRepository repository = new RecordingRepository(active, target);
        RecordingIndexManager indexManager = new RecordingIndexManager();

        CapabilityConfigServiceImpl service = new CapabilityConfigServiceImpl(
                repository,
                null,
                null,
                null,
                new CapabilityResolver(repository),
                null);
        service.setSegmentIndexManager(indexManager);

        service.select("EMBEDDING", 2L);

        assertNotNull(indexManager.requestedProfile);
        assertEquals("new-model", indexManager.requestedProfile.modelName());
        assertFalse(repository.selected);
        assertFalse(repository.disabled);
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

    private static final class RecordingIndexManager implements SegmentIndexManager {
        private EmbeddingProfile requestedProfile;

        @Override
        public void asyncCreate() {}

        @Override
        public boolean retryCreate() {
            return false;
        }

        @Override
        public boolean confirmRebuild(String taskId) {
            return false;
        }

        @Override
        public String prepareRebuild() {
            return null;
        }

        @Override
        public String requestRebuild(EmbeddingProfile targetProfile) {
            requestedProfile = targetProfile;
            return "task-1";
        }

        @Override
        public SegmentIndexStatusDTO status() {
            return null;
        }
    }
}
