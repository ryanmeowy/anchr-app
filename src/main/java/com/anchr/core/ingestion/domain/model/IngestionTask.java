package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ingestion task aggregate for a knowledge base.
 */
@Value
@Builder(toBuilder = true)
public class IngestionTask {

    String id;
    String kbId;
    IngestionSourceType sourceType;
    String clientRequestId;
    String requestHash;
    IngestionTaskStatus status;
    int totalCount;
    int successCount;
    int failureCount;
    int runningCount;
    String createdBy;
    String updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime finishedAt;
    List<IngestionTaskItem> items;
}
