package com.anchr.core.search.escli.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Simplified request model for document search endpoint.
 */
@Data
public class EsDocSearchRequestDTO {

    @NotBlank(message = "query cannot be empty")
    private String query;

    @Min(value = 0, message = "from must be >= 0")
    private Integer from = 0;

    @Min(value = 1, message = "size must be >= 1")
    @Max(value = 100, message = "size must be <= 100")
    private Integer size = 10;

    /**
     * Sort expressions, e.g. ["_score:desc", "createTime:desc"].
     */
    private List<String> sort;

    private List<String> sourceIncludes;
}
