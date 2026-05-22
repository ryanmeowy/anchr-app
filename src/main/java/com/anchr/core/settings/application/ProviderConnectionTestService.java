package com.anchr.core.settings.application;

import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.ProviderConnectionTestResultDTO;

/**
 * Application service for real provider connection checks.
 */
public interface ProviderConnectionTestService {

    ProviderConnectionTestResultDTO test(ProviderType providerType, String providerName);
}
