package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

import java.util.Map;

/**
 * Search hit model for doc search.
 */
@Builder
public record EsDocHitDTO(
        String id,
        Double score,
        Map<String, Object> source
) {
}
