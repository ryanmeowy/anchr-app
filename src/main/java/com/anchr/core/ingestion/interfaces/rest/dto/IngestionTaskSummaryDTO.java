package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionTask;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Ingestion task list item response.
 */
@Value
@Builder
public class IngestionTaskSummaryDTO {

    String taskId;
    String kbId;
    String sourceType;
    String status;
    int totalCount;
    int successCount;
    int failureCount;
    int runningCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static IngestionTaskSummaryDTO from(IngestionTask task) {
        return IngestionTaskSummaryDTO.builder()
                .taskId(task.getId())
                .kbId(task.getKbId())
                .sourceType(task.getSourceType().name())
                .status(task.getStatus().name())
                .totalCount(task.getTotalCount())
                .successCount(task.getSuccessCount())
                .failureCount(task.getFailureCount())
                .runningCount(task.getRunningCount())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
