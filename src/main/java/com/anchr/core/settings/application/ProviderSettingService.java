package com.anchr.core.settings.application;

import com.anchr.core.settings.application.model.ProviderSwitchResult;
import com.anchr.core.settings.domain.model.ProviderType;

/**
 * Application service for runtime provider selection.
 */
public interface ProviderSettingService {

    ProviderSwitchResult switchProvider(ProviderType providerType, String providerName);
}
