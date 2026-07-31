package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record RuntimeConfigParamDTO(
        @NotBlank String key,
        @NotBlank String value
) {
}
