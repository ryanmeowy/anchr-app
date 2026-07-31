package com.anchr.core.settings.application.api;

import java.util.Optional;

/**
 * Provider-side application API for querying one persisted runtime setting.
 */
public interface RuntimeConfigQueryApi {

    Optional<String> findValue(String type, String key);
}
