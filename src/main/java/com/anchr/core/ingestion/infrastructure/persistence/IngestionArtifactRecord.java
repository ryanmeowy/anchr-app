package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for an immutable ingestion artifact registration.
 */
@Data
public class IngestionArtifactRecord {

    private Long executionId;
    private String artifactType;
    private Integer artifactVersion;
    private Long producerClaimVersion;
    private String objectKey;
    private String contentSha256;
    private String provenance;
    private LocalDateTime createdAt;
}
