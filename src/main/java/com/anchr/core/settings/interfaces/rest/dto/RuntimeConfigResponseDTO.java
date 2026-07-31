package com.anchr.core.settings.interfaces.rest.dto;

import java.util.List;

public record RuntimeConfigResponseDTO(List<RuntimeConfigGroupDTO> groups) {
    public RuntimeConfigResponseDTO {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
