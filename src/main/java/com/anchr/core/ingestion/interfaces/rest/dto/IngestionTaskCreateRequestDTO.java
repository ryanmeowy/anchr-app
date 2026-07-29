package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request body for creating a knowledge base ingestion task.
 */
@Data
public class IngestionTaskCreateRequestDTO {

    /**
     * Client-generated idempotency key. Optional during the rolling frontend migration; requests
     * without it retain the legacy create-every-time behavior.
     */
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9._:-]+")
    private String clientRequestId;

    private IngestionSourceType sourceType = IngestionSourceType.UPLOAD;

    private DedupeStrategy dedupeStrategy = DedupeStrategy.SKIP;

    @NotEmpty
    @Size(max = 50)
    private List<@Valid IngestionTaskCreateItemDTO> items;
}
