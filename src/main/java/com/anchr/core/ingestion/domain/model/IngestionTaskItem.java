package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * A single file or URL in an ingestion task.
 */
@Value
@Builder(toBuilder = true)
public class IngestionTaskItem {

    String id;
    String taskId;
    String kbId;
    String taskCreatedBy;
    String assetId;
    String fileName;
    String fileHash;
    String sourceUrl;
    @Builder.Default
    int parseAttempt = 1;
    String doclingRequestId;
    String doclingJobId;
    String sourceRevision;
    IngestionExecutionStage executionStage;
    @Builder.Default
    long executionEpoch = 1L;
    int stageAttempt;
    int stageRetryCount;
    LocalDateTime stageStartedAt;
    LocalDateTime nextActionAt;
    String leaseToken;
    LocalDateTime leaseUntil;
    String parseRequestSnapshot;
    String parseResultObjectKey;
    String embeddingResultObjectKey;
    IngestionStage stage;
    IngestionTaskItemStatus status;
    int progress;
    DedupeStrategy dedupeStrategy;
    DedupeResult dedupeResult;
    String duplicateAssetId;
    String errorCode;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime finishedAt;
}
