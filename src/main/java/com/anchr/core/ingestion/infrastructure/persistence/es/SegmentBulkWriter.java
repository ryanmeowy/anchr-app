package com.anchr.core.ingestion.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
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
public class SegmentBulkWriter {

    private final ElasticsearchClient esClient;
    private final SegmentIndexConfig kbSegmentConfig;
    private final SegmentIndexManager segmentIndexManager;
    private final SegmentIndexWriteBarrier indexWriteBarrier;

    public void write(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        validateSegments(segments);
        indexWriteBarrier.withWritePermit(
                () -> doWrite(segments, kbSegmentConfig.getWriteTargetName(), true));
    }

    public void write(List<Segment> segments, String expectedProfileFingerprint) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        validateSegments(segments);
        if (!StringUtils.hasText(expectedProfileFingerprint)) {
            throw new IllegalArgumentException("Expected embedding profile fingerprint is required");
        }
        indexWriteBarrier.withWritePermit(() -> {
            var snapshot = segmentIndexManager.runtimeSnapshot();
            if (!expectedProfileFingerprint.equals(snapshot.profile().fingerprint())) {
                throw new BusinessException(
                        ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "Embedding profile changed before index write; item must be re-embedded");
            }
            // Acquiring the distributed lease is the authoritative acceptance
            // point. A cutover that begins immediately afterwards must drain this
            // write instead of rejecting it via the status projection.
            doWrite(segments, snapshot.physicalIndex(), false);
        });
    }

    private void validateSegments(List<Segment> segments) {
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment == null) {
                throw new IllegalArgumentException(
                        "segments[" + index + "] cannot be null.");
            }
            if (!StringUtils.hasText(segment.getSegmentId())) {
                throw new IllegalArgumentException(
                        "segments[" + index + "].segmentId cannot be blank.");
            }
        }
    }

    private void doWrite(List<Segment> segments, String indexName, boolean verifyStatus) {
        if (verifyStatus) {
            SegmentIndexStatusDTO status = segmentIndexManager.status();
            if (!status.isWritable()) {
                throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "Search index is not writable, current status: " + status.getStatus());
            }
        }
        try {
            var requestBuilder = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
            requestBuilder.refresh(Refresh.WaitFor);
            for (Segment segment : segments) {
                requestBuilder.operations(op -> op.index(i -> i
                        .index(indexName)
                        .id(segment.getSegmentId())
                        .document(toDocument(segment))
                ));
            }
            BulkResponse response = esClient.bulk(requestBuilder.build());
            List<BulkResponseItem> responseItems =
                    response == null ? null : response.items();
            if (responseItems == null || responseItems.size() != segments.size()) {
                int actualCount = responseItems == null ? 0 : responseItems.size();
                throw new BusinessException(
                        ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "kb_segment bulk response size mismatch: expected "
                                + segments.size() + ", actual " + actualCount);
            }
            List<BulkResponseItem> failures = responseItems.stream()
                    .filter(item -> item.error() != null)
                    .toList();
            if (response.errors() || !failures.isEmpty()) {
                String reason = failures.stream()
                        .map(BulkResponseItem::error)
                        .map(error -> error.reason())
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse("kb_segment bulk save failed");
                throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE, reason);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to persist segments to index [{}]", indexName, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE, "Failed to persist segments", e);
        }
    }

    private SegmentDocument toDocument(Segment segment) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(segment.getSegmentId());
        document.setKbId(segment.getKbId());
        document.setAssetId(segment.getAssetId());
        document.setIndexGeneration(segment.getIndexGeneration());
        document.setAssetType(segment.getAssetType());
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
