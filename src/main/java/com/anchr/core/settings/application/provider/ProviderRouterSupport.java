package com.anchr.core.settings.application.provider;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.application.impl.ProviderSelectionService;
import com.anchr.core.settings.domain.model.ProviderType;
import lombok.RequiredArgsConstructor;

/**
 * Shared provider router lookup.
 */
@RequiredArgsConstructor
public abstract class ProviderRouterSupport {

    private final ProviderSelectionService providerSelectionService;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;

    protected <T> T delegate(ProviderType providerType, Class<T> portType) {
        String providerName = providerSelectionService.resolve(providerType);
        ProviderIdentity provider = providerRuntimeRegistry.find(providerType, providerName)
                .orElseThrow(() -> new BusinessException(ApiError.PROVIDER_UNAVAILABLE,
                        "Provider is not available: " + providerType.name() + "/" + providerName));
        if (!portType.isInstance(provider)) {
            throw new BusinessException(ApiError.PROVIDER_UNAVAILABLE,
                    "Provider does not support requested capability: " + providerType.name() + "/" + providerName);
        }
        return portType.cast(provider);
    }
}
