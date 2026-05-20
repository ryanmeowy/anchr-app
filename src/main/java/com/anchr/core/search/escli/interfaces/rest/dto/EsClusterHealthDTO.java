package com.anchr.core.search.escli.interfaces.rest.dto;

import lombok.Builder;

/**
 * Cluster health view model for ES CLI API.
 */
@Builder
public record EsClusterHealthDTO(
        String status,
        String clusterName,
        Integer numberOfNodes,
        Integer activePrimaryShards,
        Integer activeShards,
        Integer unassignedShards,
        Boolean timedOut,
        String timestamp
) {
}
