package com.anchr.core.settings.application;

import com.anchr.core.settings.interfaces.rest.dto.CapabilitiesDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderListDTO;

/**
 * Query service for settings overview.
 */
public interface SettingsQueryService {

    CapabilitiesDTO capabilities();

    ProviderListDTO providers();
}
