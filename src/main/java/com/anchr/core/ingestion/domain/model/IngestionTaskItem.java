package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** One business item in an ingestion task. */
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
