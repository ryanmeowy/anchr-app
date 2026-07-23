package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker projection for one successfully claimed current execution.
 *
 * <p>The mapper may populate {@code requestSnapshot} only for parse phases.
 * Public item progress and error text are intentionally not part of this
 * execution projection.</p>
 */
@Data
public class ClaimedExecutionRecord {

    private String itemId;
    private String taskId;
    private String kbId;
    private String taskCreatedBy;
    private String assetId;
    private String sourceUrl;
    private Integer itemProgress;
    private LocalDateTime claimUpdatedAt;
    private String dedupeResult;
    private String duplicateAssetId;

    private Long executionEpoch;
    private String phase;
    private Long claimVersion;
    private Integer phaseRetryCount;
    private LocalDateTime phaseStartedAt;
    private LocalDateTime nextActionAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;

    private Integer parseAttemptNo;
    private String requestId;
    private String jobId;
    private String sourceRevision;
    private String requestSnapshot;
    private String parseResultObjectKey;
    private Integer parseResultArtifactVersion;
    private String parseResultArtifactProvenance;
    private Long parseResultProducerClaimVersion;
    private String parseResultSha256;
}
