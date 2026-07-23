package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable registry metadata for an ingestion checkpoint.
 *
 * <p>{@code LEGACY_BACKFILL} references may not have a digest because the
 * original compressed bytes predate the registry. Every newly
 * {@code PRODUCED} reference must carry a SHA-256 digest.</p>
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
