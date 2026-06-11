package com.anchr.core.integration.multimodal.embedding;

import com.anchr.core.auth.infrastructure.AesUtil;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
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
        return embedImageInternal(imageInput);
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String contentType) {
        String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        return embedImageInternal(dataUrl);
    }

    private List<Float> embedImageInternal(String imageInput) {
        CapabilityConfig config = loadConfig();
        if (config.getImageModel() == null || config.getImageEndpoint() == null) {
            throw new IllegalStateException("Image embedding is not configured. Set image_model and image_endpoint.");
        }
        OpenAiClient client = new OpenAiClient(config.getImageEndpoint(), decrypt(config.getApiKeyEnc()));
        JsonNode result = client.embeddings(config.getImageModel(), List.of(imageInput), null);
        return parseEmbeddingVector(result);
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

    static List<Float> parseEmbeddingVector(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new OpenAiClient.OpenAiException(-1, "Empty embedding response.");
        }
        List<Float> vector = new ArrayList<>();
        for (JsonNode val : data.get(0).path("embedding")) {
            vector.add((float) val.asDouble());
        }
        return vector;
    }
}
