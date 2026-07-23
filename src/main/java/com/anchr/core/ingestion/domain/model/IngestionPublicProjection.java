package com.anchr.core.ingestion.domain.model;

import java.util.Objects;

/**
 * Client-visible state derived from an ingestion lifecycle event.
 *
 * <p>The execution model remains the source of truth for scheduling. This
 * projection only preserves the coarse-grained stage, status and progress
 * contract exposed by the ingestion API.</p>
 */
public record IngestionPublicProjection(
        IngestionStage stage,
        IngestionTaskItemStatus status,
        int progress) {

    public IngestionPublicProjection {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(status, "status");
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException(
                    "Public ingestion progress must be between 0 and 100.");
        }
    }
}
