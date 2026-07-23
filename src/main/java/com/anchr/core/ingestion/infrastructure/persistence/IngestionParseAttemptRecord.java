package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for one stable Docling parse identity.
 */
@Data
public class IngestionParseAttemptRecord {

    private Long id;
    private String itemId;
    private Integer attemptNo;
    private String requestId;
    private String jobId;
    private String sourceRevision;
    private String requestSnapshot;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
