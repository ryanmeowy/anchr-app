package com.anchr.core.settings.application.api;

import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;

import java.util.Optional;

/**
 * Provider-side application API for querying one persisted runtime setting.
 */
public interface RuntimeConfigQueryApi {

    Optional<String> findValue(RuntimeConfigType type, RuntimeConfigKey key);
}
