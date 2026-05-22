package com.anchr.core.kb.infrastructure.persistence;

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
    private String stage;
    private String status;
    private Integer progress;
    private String dedupeResult;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
