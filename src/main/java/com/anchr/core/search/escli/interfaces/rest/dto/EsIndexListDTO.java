package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

import java.util.List;

/**
 * Paged index list response model.
 */
@Builder
public record EsIndexListDTO(
        List<EsIndexSummaryDTO> items,
        Integer page,
        Integer size,
        Integer total
) {
}
