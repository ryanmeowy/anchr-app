package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Complete next state for a fenced ingestion-stage transition.
 *
 * <p>Every mutable scheduler, public projection, parse identity, artifact and
 * error field is written exactly as supplied. A {@code null} value therefore
 * means that the corresponding nullable database field must be cleared. When
 * entering a different execution stage, callers set {@code nextStageStartedAt}
 * to the transition time. Same-stage polling or backoff must carry the existing
 * stage start forward so a retry cannot reset the stage timeout.</p>
 */
@Value
@Builder(toBuilder = true)
public class IngestionClaimTransition {

    String itemId;
    String taskId;
    String kbId;
    long executionEpoch;
    IngestionExecutionStage expectedExecutionStage;
    long expectedClaimVersion;
    String leaseToken;
    boolean retainLease;

    IngestionExecutionStage nextExecutionStage;
    int nextStageRetryCount;
    LocalDateTime nextStageStartedAt;
    LocalDateTime nextActionAt;

    IngestionStage stage;
    IngestionTaskItemStatus status;
    int progress;

    int parseAttempt;
    String doclingRequestId;
    String doclingJobId;
    String sourceRevision;
    String parseRequestSnapshot;
    String parseResultObjectKey;
    String parseResultSha256;

    String errorCode;
    String errorMessage;
    LocalDateTime finishedAt;
    String updatedBy;
    LocalDateTime updatedAt;
}
