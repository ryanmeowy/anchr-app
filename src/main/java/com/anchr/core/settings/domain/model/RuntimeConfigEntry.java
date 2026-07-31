package com.anchr.core.settings.domain.model;

import java.time.LocalDateTime;

public record RuntimeConfigEntry(
        RuntimeConfigType type,
        String key,
        String value,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
