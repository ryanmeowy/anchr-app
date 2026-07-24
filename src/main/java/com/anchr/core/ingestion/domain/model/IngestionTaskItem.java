package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Application compatibility carrier for one ingestion item.
 *
 * <p>Persistence no longer maps this class to one physical row: public reads,
 * retry preparation and claimed execution loading use separate records. The
 * remaining flat application shape preserves the existing service/DTO and
 * processor contracts until the later aggregate-boundary refactor.</p>
 */
@Value
@Builder(toBuilder = true)
public class IngestionTaskItem {

    String id;
    String taskId;
    String kbId;
    String taskCreatedBy;
    String assetId;
    Long targetIndexGeneration;
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
    long claimVersion;
    int stageRetryCount;
    LocalDateTime stageStartedAt;
    LocalDateTime nextActionAt;
    String leaseToken;
    LocalDateTime leaseUntil;
    String parseRequestSnapshot;
    String parseResultObjectKey;
    IngestionArtifactReference parseResultArtifact;
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
