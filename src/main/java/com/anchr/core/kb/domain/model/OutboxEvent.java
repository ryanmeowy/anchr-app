package com.anchr.core.kb.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable event written in the same transaction as a knowledge-base change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    private Long id;
    private OutboxEventType eventType;
    private String aggregateType;
    private String aggregateId;
    private String payload;
    private OutboxEventStatus status;
    private int retryCount;
    private LocalDateTime nextRetryAt;
    private String lockToken;
    private LocalDateTime lockedAt;
    private LocalDateTime processedAt;
    private String lastError;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
