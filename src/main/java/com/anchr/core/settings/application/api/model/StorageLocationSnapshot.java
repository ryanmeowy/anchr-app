package com.anchr.core.settings.application.api.model;

/** Immutable public location facts for the active object-storage configuration. */
public record StorageLocationSnapshot(
        String endpoint,
        String bucket,
        String region,
        String prefix
) {
}
