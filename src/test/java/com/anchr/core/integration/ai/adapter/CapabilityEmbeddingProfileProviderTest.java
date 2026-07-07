package com.anchr.core.integration.ai.adapter;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityEmbeddingProfileProviderTest {

    @Test
    void createProfileShouldCanonicalizeUrlAndExtraConfig() {
        Optional<EmbeddingProfile> first = CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com/", "model-a",
                        "{\"dimensions\":1024,\"options\":{\"b\":2,\"a\":1}}"));
        Optional<EmbeddingProfile> second = CapabilityEmbeddingProfileProvider.createProfile(
                config(2L, "https://embedding.example.com", "model-a",
                        "{\"options\":{\"a\":1,\"b\":2},\"dimensions\":1024}"));

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(first.orElseThrow().fingerprint(), second.orElseThrow().fingerprint());
        assertEquals(1024, first.orElseThrow().dimension());
    }

    @Test
    void createProfileShouldIgnoreNonDimensionExtraConfig() {
        EmbeddingProfile first = CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com", "model-a",
                        "{\"dimensions\":1024,\"timeout\":30}")).orElseThrow();
        EmbeddingProfile second = CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com", "model-a",
                        "{\"dimensions\":1024,\"timeout\":60}")).orElseThrow();

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void createProfileShouldChangeFingerprintWhenModelChanges() {
        EmbeddingProfile first = CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com", "model-a",
                        "{\"dimensions\":1024}")).orElseThrow();
        EmbeddingProfile second = CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com", "model-b",
                        "{\"dimensions\":1024}")).orElseThrow();

        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void createProfileShouldRejectNonPositiveDimension() {
        assertTrue(CapabilityEmbeddingProfileProvider.createProfile(
                config(1L, "https://embedding.example.com", "model-a",
                        "{\"dimensions\":0}")).isEmpty());
    }

    private CapabilityConfig config(
            Long id,
            String baseUrl,
            String modelName,
            String extraConfig
    ) {
        return CapabilityConfig.builder()
                .id(id)
                .capability("EMBEDDING")
                .baseUrl(baseUrl)
                .modelName(modelName)
                .extraConfig(extraConfig)
                .enabled(true)
                .build();
    }
}
