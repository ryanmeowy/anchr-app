package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.IngestionTask;
import lombok.Builder;
import lombok.Value;

/**
 * Response for reparse/reembed operations.
 */
@Value
@Builder
public class DocumentMaintenanceTaskDTO {

    String taskId;
    String assetId;
    String status;

    public static DocumentMaintenanceTaskDTO from(IngestionTask task, String assetId) {
        return DocumentMaintenanceTaskDTO.builder()
                .taskId(task.getId())
                .assetId(assetId)
                .status(task.getStatus().name())
                .build();
    }
}
