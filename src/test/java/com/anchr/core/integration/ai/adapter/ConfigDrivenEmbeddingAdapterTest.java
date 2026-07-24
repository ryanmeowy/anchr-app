package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
