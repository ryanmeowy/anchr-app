package com.anchr.core.search.interfaces.rest.dto;

import com.anchr.core.search.domain.model.StrategyTypeEnum;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Text search request model.
 * <p>
 * Parameter semantics:
 * <ul>
 *     <li>{@code limit}: final response size upper bound.</li>
 *     <li>{@code topK}: vector recall size (KNN k), only effective for strategies with vector retrieval.</li>
 * </ul>
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Data
public class SearchQueryDTO {
    /**
     * search keyword
     */
    @NotBlank(message = "keyword cannot be empty")
    @Size(max = 50, message = "keyword length cannot exceed 50")
    private String keyword;
    /**
     * Vector recall size (KNN {@code k}).
     * Effective for HYBRID(0) and VECTOR_ONLY(1), ignored for TEXT_ONLY(2).
     */
    @Min(value = 1, message = "topK must be greater than 0")
    @Max(value = 200, message = "topK cannot exceed 200")
    private Integer topK;
    /**
     * page number
     */
    private Integer pageNo;
    /**
     * Deprecated for retrieval flow.
     * Kept for backward compatibility of callers that still send the field.
     */
    @Deprecated
    private Float similarity;
    /**
     * whether to enable OCR hybrid search
     */
    private Boolean enableOcr = true;
    /**
     * search strategy type
     * @see StrategyTypeEnum
     */
    @Pattern(regexp = "[0-3]", message = "searchType must be one of 0,1,2,3")
    private String searchType;

    /**
     * Final response size upper bound.
     */
    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 200, message = "limit cannot exceed 200")
    private Integer limit;

}
