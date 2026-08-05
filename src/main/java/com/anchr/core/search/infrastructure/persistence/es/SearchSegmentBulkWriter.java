package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

import static com.anchr.core.common.constant.SegmentIndexConstant.WRITE_ALIAS;

/**
 * Shared bulk writer for kb_segment index.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchSegmentBulkWriter {

    private final ElasticsearchClient esClient;
    private final SegmentIndexManager segmentIndexManager;
    private final SegmentIndexWriteBarrier indexWriteBarrier;
    private final SegmentRebuildMutationTracker rebuildMutationTracker;

    public WriteResult write(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new WriteResult(0, WRITE_ALIAS, null);
        }
        validateSegments(segments);
        return indexWriteBarrier.withWritePermit(() -> doWrite(segments));
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

    private WriteResult doWrite(List<Segment> segments) {
        SegmentIndexStatusDTO status = segmentIndexManager.status();
        if (!status.isWritable()) {
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE,
                    "Search index is not writable, current status: " + status.getStatus());
        }
        String indexName = WRITE_ALIAS;
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
                        .filter(Objects::nonNull)
                        .map(ErrorCause::reason)
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse("kb_segment bulk save failed");
                throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE, reason);
            }
            segments.stream()
                    .map(Segment::getAssetId)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .forEach(rebuildMutationTracker::markDirty);
            return new WriteResult(
                    segments.size(), indexName, status.getActualProfileFingerprint());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to persist segments to index [{}]", indexName, e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE, "Failed to persist segments", e);
        }
    }

    public record WriteResult(int writtenCount, String indexName, String profileFingerprint) {
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
