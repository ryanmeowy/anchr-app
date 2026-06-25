package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence projection for knowledge base stats.
 */
@Data
public class KnowledgeBaseStatsRecord {

    private String kbId;
    private Integer documentCount;
    private Integer segmentCount;
    private LocalDateTime lastIngestedAt;
    private String lastIngestionStatus;
    private Integer lastIngestionTotalCount;
    private Integer lastIngestionSuccessCount;
    private Integer lastIngestionFailureCount;
    private Integer lastIngestionRunningCount;
    private LocalDateTime updatedAt;
}
