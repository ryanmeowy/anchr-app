package com.anchr.core.settings.domain.model;

import java.time.LocalDateTime;

public record RuntimeConfigEntry(
        RuntimeConfigType type,
        RuntimeConfigKey key,
        String value,
        String updatedBy,
        LocalDateTime updatedAt
) {
    public RuntimeConfigEntry {
        key.requireType(type);
    }
}
