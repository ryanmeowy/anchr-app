package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for outbox_event.
 */
@Data
public class OutboxEventRecord {

    private Long id;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lockToken;
    private LocalDateTime lockedAt;
    private LocalDateTime processedAt;
    private String lastError;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
