package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RuntimeConfigUpdateRequestDTO(
        @NotBlank String type,
        @NotEmpty List<@Valid RuntimeConfigParamDTO> params
) {
    public RuntimeConfigUpdateRequestDTO {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
