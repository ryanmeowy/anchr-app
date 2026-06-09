package com.anchr.core.settings.domain.model;

import java.util.Locale;

/**
 * Configurable provider capability types.
 */
public enum ProviderType {
    GENERATION,
    EMBEDDING,
    RERANK,
    OCR,
    OBJECT_STORAGE,
    WEB_SEARCH;

    public static ProviderType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("providerType cannot be blank.");
        }
        return ProviderType.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
