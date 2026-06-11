package com.anchr.core.settings.application.provider;

import com.anchr.core.common.config.EmbeddingProperties;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.domain.model.ProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;

/**
 * Shared provider router lookup. Resolves provider selection from configuration.
 */
@RequiredArgsConstructor
public abstract class ProviderRouterSupport {

    private final ProviderRuntimeRegistry providerRuntimeRegistry;

    @Value("${app.capability-provider.gen:aliyun}")
    private String generationProvider;

    @Value("${app.capability-provider.rerank:aliyun}")
    private String rerankProvider;

    @Value("${app.capability-provider.ocr:aliyun}")
    private String ocrProvider;

    @Value("${app.capability-provider.object-storage:aliyun}")
    private String objectStorageProvider;

    private final EmbeddingProperties embeddingProperties;

    protected <T> T delegate(ProviderType providerType, Class<T> portType) {
        String providerName = resolve(providerType);
        ProviderIdentity provider = providerRuntimeRegistry.find(providerType, providerName)
                .orElseThrow(() -> new BusinessException(ApiError.PROVIDER_UNAVAILABLE,
                        "Provider is not available: " + providerType.name() + "/" + providerName));
        if (!portType.isInstance(provider)) {
            throw new BusinessException(ApiError.PROVIDER_UNAVAILABLE,
                    "Provider does not support requested capability: " + providerType.name() + "/" + providerName);
        }
        return portType.cast(provider);
    }

    private String resolve(ProviderType providerType) {
        return switch (providerType) {
            case GENERATION -> generationProvider;
            case EMBEDDING -> embeddingProperties.getBackend();
            case RERANK -> rerankProvider;
            case OCR -> ocrProvider;
            case OBJECT_STORAGE -> objectStorageProvider;
        };
    }
}
