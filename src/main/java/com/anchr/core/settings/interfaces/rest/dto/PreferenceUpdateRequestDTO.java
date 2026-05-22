package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Appearance preference update request.
 */
@Data
public class PreferenceUpdateRequestDTO {
    @NotBlank
    private String theme;
}
