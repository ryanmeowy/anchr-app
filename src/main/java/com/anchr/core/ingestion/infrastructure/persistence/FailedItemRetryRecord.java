package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Minimal projection used to prepare an explicit retry with a current-execution fence.
 */
@Data
public class FailedItemRetryRecord {

    private String itemId;
    private String taskId;
    private String kbId;
    private String itemStatus;
    private LocalDateTime itemUpdatedAt;

    private Long currentExecutionId;
    private Long executionEpoch;
    private String executionStatus;

    private Integer parseAttemptNo;
    private String sourceRevision;
}
