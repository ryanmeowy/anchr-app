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
    private Long currentExecutionId;
    /**
     * Expand-phase compatibility copy for the legacy non-null item column.
     *
     * <p>New read paths obtain the knowledge-base scope from the parent task.</p>
     */
    private String kbId;
    private String assetId;
    private String fileName;
    private String fileHash;
    private String sourceUrl;
    private String stage;
    private String status;
    private Integer progress;
    /**
     * Expand-phase compatibility copy for rollback to the legacy item model.
     *
     * <p>The parent task is the source of truth in the split model.</p>
     */
    private String dedupeStrategy;
    private String dedupeResult;
    private String duplicateAssetId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
