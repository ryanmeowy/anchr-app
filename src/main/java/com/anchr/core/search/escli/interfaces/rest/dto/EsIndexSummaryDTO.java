package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

/**
 * Index summary row for index list API.
 */
@Builder
public record EsIndexSummaryDTO(
        String name,
        String health,
        Long docsCount,
        String storeSize,
        String pri,
        String rep
) {
}
