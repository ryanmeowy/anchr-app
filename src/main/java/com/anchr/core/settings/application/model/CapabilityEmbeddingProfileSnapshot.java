package com.anchr.core.settings.application.model;

/** Capability-owned non-sensitive embedding profile snapshot. */
public record CapabilityEmbeddingProfileSnapshot(
        Long configId,
        String capability,
        String modelName,
        int dimension,
        String fingerprint
) {
}
