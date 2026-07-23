package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for one durable ingestion execution.
 *
 * <p>An execution keeps its terminal outcome and last real phase after the item
 * advances to a later explicit retry.</p>
 */
@Data
public class IngestionExecutionRecord {

    private Long id;
    private String itemId;
    private Long parseAttemptId;
    private Long executionEpoch;
    private String executionKind;
    private String phase;
    private String executionStatus;
    private Long claimVersion;
    private Integer phaseRetryCount;
    private LocalDateTime phaseStartedAt;
    private LocalDateTime nextActionAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
