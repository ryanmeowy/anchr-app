package com.anchr.core.ingestion.domain.model;

/** Durable cleanup request for files owned by one failed parse attempt. */
public record IngestionAttemptArtifactDeletePayload(
        String taskId,
        String itemId,
        long executionEpoch,
        int parseAttempt,
        String imagePrefix,
        String parseArtifactPrefix) {
}
