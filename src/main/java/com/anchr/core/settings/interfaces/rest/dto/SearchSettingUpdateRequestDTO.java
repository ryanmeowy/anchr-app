package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Search settings update request.
 */
@Data
public class SearchSettingUpdateRequestDTO {
    @Min(1)
    @Max(100)
    private Integer topK;

    @Min(1)
    @Max(200)
    private Integer rerankWindow;

    @Min(1)
    @Max(200)
    private Integer rrfK;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double minScore;
}
