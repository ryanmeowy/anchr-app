package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for ingestion_task.
 */
@Data
public class IngestionTaskRecord {

    private String id;
    private String kbId;
    private String sourceType;
    private String clientRequestId;
    private String requestHash;
    private String dedupeStrategy;
    private String status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private Integer runningCount;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
