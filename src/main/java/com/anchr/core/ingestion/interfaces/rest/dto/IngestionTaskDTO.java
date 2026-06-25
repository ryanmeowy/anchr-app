package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionTask;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ingestion task response DTO.
 */
@Value
@Builder
public class IngestionTaskDTO {

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
    LocalDateTime finishedAt;
    List<IngestionTaskItemDTO> items;

    public static IngestionTaskDTO from(IngestionTask task) {
        return IngestionTaskDTO.builder()
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
                .finishedAt(task.getFinishedAt())
                .items(task.getItems() == null ? List.of() : task.getItems().stream().map(IngestionTaskItemDTO::from).toList())
                .build();
    }
}
