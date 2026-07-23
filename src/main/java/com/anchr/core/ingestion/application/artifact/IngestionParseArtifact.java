package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.ParseResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Versioned durable representation of one successful Docling parse result.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestionParseArtifact(
        String artifactType,
        int version,
        String taskId,
        String itemId,
        String kbId,
        String assetId,
        int parseAttempt,
        String jobId,
        String requestId,
        String sourceRevision,
        Instant createdAt,
        ParseResponse result
) {
}
