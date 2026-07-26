package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                new ConfigDrivenEmbeddingAdapter(cacheManager, null, null);

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
                        List.of(java.util.Map.of("text", "query text")));
        assertThat(client.contexts.get(1).contentMap())
                .containsEntry(
                        "contents",
                        List.of(java.util.Map.of(
                                "image",
                                "https://example.test/original.png")));
    }

    @Test
    void targetSessionShouldResolveItsExactConfigInsteadOfTheActiveClient() {
        CapabilityConfig target = CapabilityConfig.builder()
                .id(9L)
                .capability("EMBEDDING")
                .baseUrl("https://target.example.test")
                .modelName("text-target")
                .extraConfig("{\"dimensions\":2}")
                .enabled(false)
                .build();
        EmbeddingProfile targetProfile = CapabilityEmbeddingProfileProvider.createProfile(target)
                .orElseThrow();
        RecordingEmbeddingClient targetClient = new RecordingEmbeddingClient();
        CapabilityConfigRepository repository = mock(CapabilityConfigRepository.class);
        CapabilityClientFactory factory = mock(CapabilityClientFactory.class);
        when(repository.findById(9L)).thenReturn(java.util.Optional.of(target));
        when(factory.build(target)).thenReturn(targetClient);
        ConfigDrivenEmbeddingAdapter adapter = new ConfigDrivenEmbeddingAdapter(
                new ClientCacheManager(), factory, mock(CapabilityResolver.class));
        adapter.setCapabilityConfigRepository(repository);

        adapter.openSession(targetProfile).embed("target text", "text");

        assertThat(targetClient.contexts).hasSize(1);
        assertThat(targetClient.contexts.getFirst().modelName()).isEqualTo("text-target");
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
