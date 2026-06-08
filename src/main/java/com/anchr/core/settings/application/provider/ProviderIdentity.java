package com.anchr.core.settings.application.provider;

import com.anchr.core.settings.domain.model.ProviderType;

/**
 * Identity exposed by switchable provider implementations.
 */
public interface ProviderIdentity {

    ProviderType providerType();

    String providerName();
}
