package com.anchr.core.kb.interfaces.rest.dto.ingestion;

import com.anchr.core.kb.domain.model.ingestion.IngestionTask;
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
