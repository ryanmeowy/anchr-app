package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

/**
 * Cluster stats summary for ES CLI API.
 */
@Builder
public record EsClusterStatsDTO(
        String clusterName,
        String status,
        Integer nodeTotal,
        Integer dataNodeCount,
        Long indexCount,
        Long docCount,
        Long storeSizeBytes,
        Integer shardsTotal
) {
}
