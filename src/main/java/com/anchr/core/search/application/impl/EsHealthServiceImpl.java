package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndexStats;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.anchr.core.search.application.EsHealthService;
import com.anchr.core.search.interfaces.rest.dto.EsHealthDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link EsHealthService} using the Elasticsearch Java client.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsHealthServiceImpl implements EsHealthService {

    private final ElasticsearchClient esClient;

    @Override
    public EsHealthDTO getEsHealth() {
        try {
            HealthResponse health = esClient.cluster().health();
            IndicesStatsResponse statsResponse = esClient.indices().stats();
            var info = esClient.info();

            // Extract index stats from the aggregated "_all" view
            IndicesStats allStats = statsResponse.all();
            IndexStats primaries = allStats != null ? allStats.primaries() : null;
            long docsCount = 0;
            long storeSizeBytes = 0;
            if (primaries != null) {
                if (primaries.docs() != null) {
                    docsCount = primaries.docs().count();
                }
                if (primaries.store() != null) {
                    storeSizeBytes = primaries.store().sizeInBytes();
                }
            }
            int indexCount = statsResponse.indices() != null ? statsResponse.indices().size() : 0;

            return EsHealthDTO.builder()
                    .connected(true)
                    .status(health.status().jsonValue())
                    .clusterName(health.clusterName())
                    .nodeCount(health.numberOfNodes())
                    .dataNodeCount(health.numberOfDataNodes())
                    .activeShards(health.activeShards())
                    .activePrimaryShards(health.activePrimaryShards())
                    .unassignedShards(health.unassignedShards())
                    .initializingShards(health.initializingShards())
                    .relocatingShards(health.relocatingShards())
                    .indices(EsHealthDTO.IndexStats.builder()
                            .count(indexCount)
                            .docsCount(docsCount)
                            .storeSizeBytes(storeSizeBytes)
                            .build())
                    .version(info.version() != null ? info.version().number() : null)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to retrieve Elasticsearch health", e);
            return EsHealthDTO.builder()
                    .connected(false)
                    .error(e.getMessage())
                    .build();
        }
    }
}
