package com.anchr.core.search.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Paged search request model.
 * <p>
 * Parameter semantics:
 * <ul>
 *     <li>{@code limit}: page size and final returned item upper bound per page.</li>
 *     <li>{@code topK}: vector recall size (KNN k) for first-page retrieval and only effective when vector retrieval is involved.</li>
 * </ul>
 */
@Data
public class SearchPageQueryDTO {

    @NotBlank(message = "keyword cannot be empty")
    @Size(max = 50, message = "keyword length cannot exceed 50")
    private String keyword;

    /**
     * Vector recall size (KNN {@code k}) for first-page retrieval.
     * Effective for HYBRID(0) and VECTOR_ONLY(1), ignored for TEXT_ONLY(2).
     */
    @Min(value = 1, message = "topK must be greater than 0")
    @Max(value = 200, message = "topK cannot exceed 200")
    private Integer topK;

    /**
     * Whether OCR keyword matching participates in search.
     */
    private Boolean enableOcr = true;

    /**
     * Strategy type code: 0/1/2/3.
     */
    @Pattern(regexp = "[0-3]", message = "searchType must be one of 0,1,2,3")
    private String searchType;

    /**
     * Page size for each page request.
     */
    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 100, message = "limit cannot exceed 100")
    private Integer limit;

    /**
     * Opaque cursor returned by previous page.
     */
    private String cursor;
}
