package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Read model for knowledge base statistics.
 */
@Value
@Builder
public class KnowledgeBaseStats {

    String kbId;
    int documentCount;
    int segmentCount;
    LocalDateTime lastIngestedAt;
    String lastIngestionStatus;
    LocalDateTime updatedAt;
}
