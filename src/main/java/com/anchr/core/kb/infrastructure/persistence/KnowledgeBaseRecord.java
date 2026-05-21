package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for the knowledge_base table.
 */
@Data
public class KnowledgeBaseRecord {

    private String id;
    private String workspaceId;
    private String name;
    private String description;
    private String status;
    private Integer documentCount;
    private Integer segmentCount;
    private LocalDateTime lastIngestedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
