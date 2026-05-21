package com.anchr.core.kb.domain.model.ingestion;

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
    String assetId;
    String fileName;
    String fileHash;
    String sourceUrl;
    IngestionStage stage;
    IngestionTaskItemStatus status;
    int progress;
    DedupeResult dedupeResult;
    String errorCode;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime finishedAt;
}
