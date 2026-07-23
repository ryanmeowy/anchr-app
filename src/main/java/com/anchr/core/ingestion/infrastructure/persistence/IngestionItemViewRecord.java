package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Narrow public item projection used by task list and detail reads.
 *
 * <p>Lease, request snapshot and artifact fields are deliberately excluded.</p>
 */
@Data
public class IngestionItemViewRecord {

    private String id;
    private String taskId;
    private String kbId;
    private String dedupeStrategy;

    private String assetId;
    private String fileName;
    private String fileHash;
    private String sourceUrl;
    private String stage;
    private String status;
    private Integer progress;
    private String dedupeResult;
    private String duplicateAssetId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
