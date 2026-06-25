package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Knowledge base aggregate root.
 */
@Value
@Builder(toBuilder = true)
public class KnowledgeBase {

    String id;
    String name;
    String description;
    KnowledgeBaseStatus status;
    int documentCount;
    int segmentCount;
    LocalDateTime lastIngestedAt;
    String createdBy;
    String updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime deletedAt;
}
