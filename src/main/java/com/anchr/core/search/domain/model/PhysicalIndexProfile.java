package com.anchr.core.search.domain.model;

/** Durable validation metadata for an immutable physical index version. */
public record PhysicalIndexProfile(
        String physicalIndex,
        Long configId,
        String profileFingerprint,
        String capability,
        String modelName,
        int vectorSchemaVersion,
        int dimension,
        long maxAppliedRevision,
        String lifecycleStatus
) {
}
