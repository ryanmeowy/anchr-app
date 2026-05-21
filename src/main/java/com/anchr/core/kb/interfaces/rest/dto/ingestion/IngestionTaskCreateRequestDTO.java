package com.anchr.core.kb.interfaces.rest.dto.ingestion;

import com.anchr.core.kb.domain.model.ingestion.DedupeStrategy;
import com.anchr.core.kb.domain.model.ingestion.IngestionSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request body for creating a knowledge base ingestion task.
 */
@Data
public class IngestionTaskCreateRequestDTO {

    private IngestionSourceType sourceType = IngestionSourceType.UPLOAD;

    private DedupeStrategy dedupeStrategy = DedupeStrategy.SKIP;

    @NotEmpty
    @Size(max = 50)
    private List<@Valid IngestionTaskCreateItemDTO> items;
}
