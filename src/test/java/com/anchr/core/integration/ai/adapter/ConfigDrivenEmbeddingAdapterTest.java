package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigDrivenEmbeddingAdapterTest {

    @Test
    void multiTextAndImageRequestsShouldUseTheSameModelAndDimensions() {
        CapabilityConfig config = CapabilityConfig.builder()
                .capability("MULTI_EMBEDDING")
                .modelName("multimodal-embedding-v1")
                .extraConfig("{\"dimensions\":1024}")
                .enabled(true)
                .build();
        RecordingEmbeddingClient client = new RecordingEmbeddingClient();
        ClientCacheManager cacheManager = new ClientCacheManager();
        cacheManager.put(
                CapabilityResolver.SLOT_EMBEDDING,
                new ClientCacheManager.ResolvedClient(client, config));
        ConfigDrivenEmbeddingAdapter adapter =
                new ConfigDrivenEmbeddingAdapter(
                        cacheManager,
                        null,
                        null,
                        mock(CapabilityConfigRepository.class));

        adapter.embed("query text", "text");
        adapter.embed("https://example.test/original.png", "image");

        assertThat(client.contexts).hasSize(2);
        assertThat(client.contexts)
                .extracting(EmbeddingClient.EmbedContext::modelName)
                .containsExactly(
                        "multimodal-embedding-v1",
                        "multimodal-embedding-v1");
        assertThat(client.contexts)
                .extracting(context -> context.extraConfig().get("dimensions"))
                .containsExactly(1024, 1024);
        assertThat(client.contexts.get(0).contentMap())
                .containsEntry(
                        "contents",
                        List.of(Map.of("text", "query text")));
        assertThat(client.contexts.get(1).contentMap())
                .containsEntry(
                        "contents",
                        List.of(Map.of(
                                "image",
                                "https://example.test/original.png")));
    }

    @Test
    void rebuildSessionShouldUseTargetConfigBeforeItBecomesActive() {
        CapabilityConfig target = CapabilityConfig.builder()
                .id(2L)
                .capability("EMBEDDING")
                .baseUrl("https://embedding.example.test")
                .modelName("new-model")
                .extraConfig("{\"dimensions\":1024}")
                .enabled(false)
                .build();
        EmbeddingProfile targetProfile = CapabilityEmbeddingProfileProvider
                .createProfile(target)
                .orElseThrow();
        RecordingEmbeddingClient targetClient = new RecordingEmbeddingClient();
        CapabilityClientFactory clientFactory =
                new CapabilityClientFactory(null) {
                    @Override
                    public Object build(CapabilityConfig config) {
                        return targetClient;
                    }
                };
        RecordingConfigRepository repository = new RecordingConfigRepository(target);
        ConfigDrivenEmbeddingAdapter adapter = new ConfigDrivenEmbeddingAdapter(
                new ClientCacheManager(), clientFactory, null, repository);

        adapter.openSession(targetProfile).embed("document text", "text");

        assertThat(repository.requestedId).isEqualTo(2L);
        assertThat(targetClient.contexts)
                .extracting(EmbeddingClient.EmbedContext::modelName)
                .containsExactly("new-model");
    }

    private static final class RecordingConfigRepository
            implements CapabilityConfigRepository {
        private final CapabilityConfig target;
        private Long requestedId;

        private RecordingConfigRepository(CapabilityConfig target) {
            this.target = target;
        }

        @Override
        public Optional<CapabilityConfig> findById(Long id) {
            requestedId = id;
            return target.getId().equals(id) ? Optional.of(target) : Optional.empty();
        }

        @Override
        public List<CapabilityConfig> findByCapability(String capability) {
            return List.of();
        }

        @Override
        public List<CapabilityConfig> findAllByCapability(String capability) {
            return List.of();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void disableAll(String capability) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void del(String capability, Long id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingEmbeddingClient implements EmbeddingClient {

        private final List<EmbedContext> contexts = new ArrayList<>();

        @Override
        public EmbeddingResult embed(EmbedContext context) {
            contexts.add(context);
            return new EmbeddingResult(List.of(1F), 1024);
        }

        @Override
        public ConnectionTestResult testConnection(String modelName) {
            throw new UnsupportedOperationException();
        }
    }
}
