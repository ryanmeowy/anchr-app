package com.anchr.core.ingestion.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.anchr.core.common.config.VectorConfig;
import com.anchr.core.ingestion.domain.model.BulkSaveResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ES Generic Batch Operations Tool
 * Solves the problem of automatic Index and ID resolution for generic entities
 *
 * @author Ryan
 * @since 2025/12/16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsBatchTemplate {
    private final ElasticsearchClient esClient;
    private final VectorConfig vectorConfig;
    private final ElasticsearchConverter elasticsearchConverter;

    /**
     * Generic batch write (supports partial failure handling)
     *
     * @param items Entity list
     * @param <T>   Entity type
     * @return Actual number of successfully written items
     */
    public <T> BulkSaveResult bulkSave(List<T> items) {
        if (items == null || items.isEmpty()) {
            return BulkSaveResult.builder()
                    .successCount(0)
                    .successIds(Collections.emptySet())
                    .failedIds(Collections.emptySet())
                    .build();
        }
        log.info("bulk save item size:{}", items.size());
        Class<?> clazz = items.getFirst().getClass();
        String indexName = vectorConfig.getWriteTargetName();

        Set<String> inputIds = new HashSet<>();
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();

            for (T item : items) {
                Object identifier = elasticsearchConverter.getMappingContext()
                        .getRequiredPersistentEntity(clazz)
                        .getIdentifierAccessor(item)
                        .getIdentifier();
                String id = Optional.ofNullable(identifier).map(String::valueOf).orElse(null);
                if (id == null) {
                    log.warn("ES Bulk write failed - ID is null for item: {}", item);
                    continue;
                }
                inputIds.add(id);
                br.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(id)
                                .document(item)
                        )
                );
            }

            BulkResponse response = esClient.bulk(br.build());
            Set<String> failedIds = new HashSet<>();

            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("Bulk write failed - Index: {}, ID: {}, Reason: {}",
                                item.index(), item.id(), item.error().reason());
                        failedIds.add(item.id());
                    }
                }
            }
            Set<String> successIds = new HashSet<>(inputIds);
            successIds.removeAll(failedIds);

            return BulkSaveResult.builder()
                    .successCount(successIds.size())
                    .successIds(successIds)
                    .failedIds(failedIds)
                    .build();

        } catch (Exception e) {
            log.error("ES Bulk IO Exception", e);
            throw new RuntimeException("ES batch write system exception");
        }
    }
}
