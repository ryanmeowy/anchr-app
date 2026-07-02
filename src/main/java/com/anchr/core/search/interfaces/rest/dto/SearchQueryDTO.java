package com.anchr.core.search.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Unified kb search request for text + image retrieval.
 */
@Data
public class SearchQueryDTO {

    /**
     * Natural language query.
     */
    @NotBlank(message = "query cannot be empty")
    @Size(max = 200, message = "query length cannot exceed 200")
    private String query;

    /**
     * Final response size upper bound. The recall candidate size for each
     * route is derived from this value by the search service.
     */
    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 200, message = "limit cannot exceed 200")
    private Integer limit;

    @Size(max = 100, message = "kbIds cannot exceed 100")
    private List<String> kbIds;

    @Size(max = 100, message = "assetIdList cannot exceed 100")
    private List<String> assetIdList;

    @Size(max = 20, message = "assetTypes cannot exceed 20")
    private List<String> assetTypes;

    @Size(max = 20, message = "hitTypes cannot exceed 20")
    private List<String> hitTypes;

    private DateRange dateRange;

    private String cursor;

    @Size(max = 32, message = "sort length cannot exceed 32")
    private String sort;

    private Boolean withAnswer;

    @Size(max = 32, message = "answerMode length cannot exceed 32")
    private String answerMode;

    @Data
    public static class DateRange {
        private Long from;
        private Long to;
    }
}
