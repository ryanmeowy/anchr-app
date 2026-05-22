package com.anchr.core.kb.interfaces.rest.dto.ingestion;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Ingestion task list response DTO.
 */
@Value
@Builder
public class IngestionTaskListDTO {

    List<IngestionTaskSummaryDTO> items;
    String nextCursor;
}
