package com.anchr.core.settings.application.impl;

import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.integration.multimodal.embedding.EmbeddingBackend;
import com.anchr.core.search.domain.port.SearchOcrPort;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.settings.application.ProviderConnectionTestService;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.config.ProviderConnectionTestProperties;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.ProviderConnectionTestResultDTO;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes real provider calls with configured test inputs.
 */
@Service
@RequiredArgsConstructor
public class ProviderConnectionTestServiceImpl implements ProviderConnectionTestService {

    private final ProviderRuntimeRegistry providerRuntimeRegistry;
    private final ProviderConnectionTestProperties properties;

    @Override
    public ProviderConnectionTestResultDTO test(ProviderType providerType, String providerName) {
        long startNs = System.nanoTime();
        ProviderIdentity provider = providerRuntimeRegistry.find(providerType, providerName)
                .orElse(null);
        if (provider == null) {
            return failure(providerType, providerName, startNs, "PROVIDER_UNAVAILABLE",
                    "Provider is not available in current process.");
        }
        try {
            execute(providerType, provider);
            return success(providerType, providerName, startNs);
        } catch (IllegalArgumentException e) {
            return failure(providerType, providerName, startNs, "TEST_INPUT_MISSING", e.getMessage());
        } catch (Exception e) {
            return failure(providerType, providerName, startNs, "CALL_FAILED", readableMessage(e));
        }
    }

    private void execute(ProviderType providerType, ProviderIdentity provider) {
        switch (providerType) {
            case GENERATION -> {
                requireText(properties.getGenerationPrompt(), "Generation test prompt is not configured.");
                ((ConversationRewritePort) provider).generateText(properties.getGenerationPrompt());
            }
            case EMBEDDING -> {
                requireText(properties.getEmbeddingText(), "Embedding test text is not configured.");
                ((EmbeddingBackend) provider).embedText(properties.getEmbeddingText());
            }
            case RERANK -> {
                requireText(properties.getRerankQuery(), "Rerank test query is not configured.");
                requireText(properties.getRerankDocument(), "Rerank test document is not configured.");
                ((SearchRerankPort) provider).rerank(properties.getRerankQuery(),
                        List.of(properties.getRerankDocument()), 1);
            }
            case OCR -> {
                requireText(properties.getOcrImageUrl(), "OCR test image URL is not configured.");
                ((SearchOcrPort) provider).extractText(properties.getOcrImageUrl());
            }
            case OBJECT_STORAGE -> {
                requireText(properties.getObjectStorageObjectKey(), "Object storage test object key is not configured.");
                ((IngestionObjectStoragePort) provider).buildDownloadUrl(properties.getObjectStorageObjectKey());
            }
            case WEB_SEARCH -> throw new IllegalArgumentException("Web search provider is not configured in this phase.");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private ProviderConnectionTestResultDTO success(ProviderType providerType, String providerName, long startNs) {
        return ProviderConnectionTestResultDTO.builder()
                .providerType(providerType.name())
                .providerName(providerName)
                .success(true)
                .latencyMs(elapsedMs(startNs))
                .code("OK")
                .message("Connection test passed.")
                .build();
    }

    private ProviderConnectionTestResultDTO failure(ProviderType providerType,
                                                    String providerName,
                                                    long startNs,
                                                    String code,
                                                    String message) {
        return ProviderConnectionTestResultDTO.builder()
                .providerType(providerType.name())
                .providerName(providerName)
                .success(false)
                .latencyMs(elapsedMs(startNs))
                .code(code)
                .message(message)
                .build();
    }

    private long elapsedMs(long startNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    }

    private String readableMessage(Exception e) {
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }
}
