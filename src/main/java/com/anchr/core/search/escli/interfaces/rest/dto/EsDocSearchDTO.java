package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

import java.util.List;

/**
 * Document search response model.
 */
@Builder
public record EsDocSearchDTO(
        Long took,
        Boolean timedOut,
        Long total,
        List<EsDocHitDTO> hits
) {
}
