package com.smart.vision.core.ingestion.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.smart.vision.core.common.config.KbSegmentConfig;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.InfraException;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.infrastructure.persistence.es.document.KbSegmentDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Shared bulk writer for kb_segment index.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSegmentBulkWriter {

    private final ElasticsearchClient esClient;
    private final KbSegmentConfig kbSegmentConfig;

    public void write(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        String indexName = kbSegmentConfig.getWriteTargetName();
        try {
            var requestBuilder = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
            int operationCount = 0;
            for (Segment segment : segments) {
                if (segment == null || !StringUtils.hasText(segment.getSegmentId())) {
                    continue;
                }
                requestBuilder.operations(op -> op.index(i -> i
                        .index(indexName)
                        .id(segment.getSegmentId())
                        .document(toDocument(segment))
                ));
                operationCount++;
            }
            if (operationCount == 0) {
                return;
            }
            BulkResponse response = esClient.bulk(requestBuilder.build());
            if (response.errors()) {
                String reason = response.items().stream()
                        .map(BulkResponseItem::error)
                        .filter(java.util.Objects::nonNull)
                        .map(error -> error.reason())
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse("kb_segment bulk save failed");
                throw new InfraException(ApiError.SEARCH_BACKEND_UNAVAILABLE, reason);
            }
        } catch (InfraException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to persist segments to index [{}]", indexName, e);
            throw new InfraException(ApiError.SEARCH_BACKEND_UNAVAILABLE, "Failed to persist segments", e);
        }
    }

    private KbSegmentDocument toDocument(Segment segment) {
        KbSegmentDocument document = new KbSegmentDocument();
        document.setSegmentId(segment.getSegmentId());
        document.setAssetId(segment.getAssetId());
        document.setAssetType(segment.getAssetType() == null ? null : segment.getAssetType().name());
        document.setSegmentType(segment.getSegmentType() == null ? null : segment.getSegmentType().name());
        document.setTitle(segment.getTitle());
        document.setContentText(segment.getContentText());
        document.setOcrText(segment.getOcrText());
        document.setPageNo(segment.getPageNo());
        document.setChunkOrder(segment.getChunkOrder());
        document.setBbox(segment.getBbox());
        document.setImageWidth(segment.getImageWidth());
        document.setImageHeight(segment.getImageHeight());
        document.setEmbedding(segment.getEmbedding());
        document.setSourceRef(segment.getSourceRef());
        document.setThumbnail(segment.getThumbnail());
        document.setOcrSummary(segment.getOcrSummary());
        document.setTags(segment.getTags());
        document.setCreatedAt(segment.getCreatedAt());
        return document;
    }
}
