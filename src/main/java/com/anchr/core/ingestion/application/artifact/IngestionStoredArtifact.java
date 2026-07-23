package com.anchr.core.ingestion.application.artifact;

import java.util.Objects;

/**
 * Immutable metadata for the exact compressed bytes persisted in object storage.
 */
public record IngestionStoredArtifact(String objectKey, int version, String sha256) {

    public IngestionStoredArtifact {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(sha256, "sha256");
        if (objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank.");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive.");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must be a 64-character lowercase hexadecimal value.");
        }
    }
}
