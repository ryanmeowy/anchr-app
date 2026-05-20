package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

import java.util.Map;

/**
 * Single document get response model.
 */
@Builder
public record EsDocGetDTO(
        Boolean found,
        String index,
        String id,
        Long version,
        Map<String, Object> source
) {
}
