package com.smart.vision.core.search.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Unified kb search request for text + image retrieval.
 */
@Data
public class KbSearchQueryDTO {

    /**
     * Natural language query.
     */
    @NotBlank(message = "query cannot be empty")
    @Size(max = 200, message = "query length cannot exceed 200")
    private String query;

    /**
     * Recall candidate size for each route.
     */
    @Min(value = 1, message = "topK must be greater than 0")
    @Max(value = 200, message = "topK cannot exceed 200")
    private Integer topK;

    /**
     * Final response size upper bound.
     */
    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 200, message = "limit cannot exceed 200")
    private Integer limit;

    /**
     * Retrieval strategy selector.
     * Current supported values: KB_RRF / KB_RRF_RERANK.
     */
    @Size(max = 32, message = "strategy length cannot exceed 32")
    private String strategy;

}
