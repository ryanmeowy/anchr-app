package com.anchr.core.integration.ai;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI-compatible embedding adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenEmbeddingAdapter implements SearchEmbeddingPort, IngestionEmbeddingPort {

    private final CapabilityConfigRepository configRepository;
    private final AesUtil aesUtil;

    @Override
    public List<Float> embedText(String text) {
        CapabilityConfig config = loadConfig();
        EmbeddingClient client = new EmbeddingClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        return client.embedText(config.getModelName(), null, text).vector();
    }

    @Override
    public List<Float> embedImage(String imageInput) {
        throw new UnsupportedOperationException("Image embedding is not supported via OpenAI-compatible API.");
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String contentType) {
        throw new UnsupportedOperationException("Image embedding is not supported via OpenAI-compatible API.");
    }

    private CapabilityConfig loadConfig() {
        return configRepository.findByCapability("EMBEDDING")
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding is not configured. Save config via PATCH /api/v1/settings/embedding."));
    }

    private String decrypt(String encrypted) {
        try {
            return aesUtil.decrypt(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt embedding apiKey.", e);
        }
    }
}
