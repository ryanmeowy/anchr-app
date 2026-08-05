package com.anchr.core.settings.domain.model;

import java.util.Locale;

public enum RuntimeConfigType {
    SEARCH,
    REBUILD,
    CONVERSATION,
    AGENT,
    INGESTION,
    OUTBOX;

    public static RuntimeConfigType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime config type is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported runtime config type: " + value);
        }
    }
}
