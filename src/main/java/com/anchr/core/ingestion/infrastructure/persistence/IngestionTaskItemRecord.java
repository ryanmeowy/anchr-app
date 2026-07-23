package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for ingestion_task_item.
 */
@Data
public class IngestionTaskItemRecord {

    private String id;
    private String taskId;
    private String kbId;
    private String assetId;
    private String fileName;
    private String fileHash;
    private String sourceUrl;
    private Integer parseAttempt;
    private String doclingRequestId;
    private String doclingJobId;
    private String sourceRevision;
    private String stage;
    private String status;
    private Integer progress;
    private String dedupeStrategy;
    private String dedupeResult;
    private String duplicateAssetId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
