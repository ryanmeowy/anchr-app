package com.anchr.core.integration.ai.client;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds AI capability clients ({@link GenerationClient}, {@link RerankClient},
 * {@link TextEmbeddingClient}/{@link MultiEmbeddingClient}) from a {@link CapabilityConfig}.
 * Shared by the config-driven adapters (cache-miss path) and the config service
 * (write-through refresh path) so client construction lives in one place.
 */
@Component
@RequiredArgsConstructor
public class CapabilityClientFactory {

    private final AesUtil aesUtil;

    public Object build(CapabilityConfig config) {
        String apiKey = decrypt(config.getApiKeyEnc());
        return switch (config.getCapability()) {
            case "GENERATION" -> new GenerationClient(config.getBaseUrl(), apiKey);
            case "RERANK" -> new RerankClient(config.getBaseUrl(), apiKey);
            case "MULTI_EMBEDDING" -> new MultiEmbeddingClient(config.getBaseUrl(), apiKey);
            default -> new TextEmbeddingClient(config.getBaseUrl(), apiKey);
        };
    }

    private String decrypt(String encrypted) {
        try {
            return aesUtil.decrypt(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt apiKey for capability "
                    + (encrypted != null ? "" : "(null)"), e);
        }
    }
}
