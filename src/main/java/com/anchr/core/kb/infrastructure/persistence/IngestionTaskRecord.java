package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for ingestion_task.
 */
@Data
public class IngestionTaskRecord {

    private String id;
    private String workspaceId;
    private String kbId;
    private String sourceType;
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
