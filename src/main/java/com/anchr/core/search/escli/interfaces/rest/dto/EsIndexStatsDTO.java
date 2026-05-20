package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

/**
 * Simplified index stats for ES CLI API.
 */
@Builder
public record EsIndexStatsDTO(
        String index,
        Long docsCount,
        Long docsDeleted,
        Long storeSizeBytes,
        Long priStoreSizeBytes,
        Long queryTotal,
        Long queryTimeInMillis
) {
}
