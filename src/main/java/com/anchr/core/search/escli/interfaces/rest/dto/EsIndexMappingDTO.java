package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

import java.util.Map;

/**
 * Index mapping response model.
 */
@Builder
public record EsIndexMappingDTO(
        String index,
        Map<String, Object> mapping
) {
}
