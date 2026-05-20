package com.anchr.core.ingestion.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Hash processing status persisted in cache.
 */
public enum ImageHashStatus {
    PROCESSING("PROCESSING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED");

    private final String value;

    ImageHashStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ImageHashStatus> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(x -> x.value.equalsIgnoreCase(raw))
                .findFirst();
    }
}

