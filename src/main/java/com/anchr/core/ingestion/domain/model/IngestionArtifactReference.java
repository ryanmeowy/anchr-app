package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable registry metadata for an ingestion checkpoint.
 *
 * <p>Every {@code PRODUCED} reference carries the producer claim and a
 * SHA-256 digest for the exact stored bytes.</p>
 */
@Value
@Builder
public class IngestionArtifactReference {

    String artifactType;
    int artifactVersion;
    String provenance;
    Long producerClaimVersion;
    String objectKey;
    String contentSha256;
}
