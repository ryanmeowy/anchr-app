package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Elasticsearch cluster health and index statistics.
 */
@Data
@Builder
public class EsHealthDTO {

    private boolean connected;
    private String status;
    private String clusterName;
    private int nodeCount;
    private int dataNodeCount;
    private int activeShards;
    private int activePrimaryShards;
    private int unassignedShards;
    private int initializingShards;
    private int relocatingShards;

    @Builder.Default
    private IndexStats indices = IndexStats.empty();

    private String version;
    private String error;

    @Data
    @Builder
    public static class IndexStats {
        private int count;
        private long docsCount;
        private long storeSizeBytes;

        public static IndexStats empty() {
            return IndexStats.builder().build();
        }
    }
}
