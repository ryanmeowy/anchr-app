package com.anchr.core.kb.interfaces.rest.dto.ingestion;

import com.anchr.core.kb.domain.model.ingestion.IngestionTask;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

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
    String failureReason;
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
                .failureReason(resolveFailureReason(task))
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private static String resolveFailureReason(IngestionTask task) {
        if (task.getItems() == null || task.getItems().isEmpty()) {
            return null;
        }
        return task.getItems().stream()
                .filter(item -> StringUtils.hasText(item.getErrorMessage()))
                .findFirst()
                .map(item -> {
                    String message = item.getErrorMessage().trim();
                    if (!StringUtils.hasText(item.getFileName())) {
                        return message;
                    }
                    return item.getFileName() + ": " + message;
                })
                .orElse(null);
    }
}
