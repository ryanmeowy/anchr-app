package com.anchr.core.settings.interfaces.rest.dto;

import java.util.List;

public record RuntimeConfigGroupDTO(
        String type,
        List<RuntimeConfigParamDTO> params
) {
    public RuntimeConfigGroupDTO {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
