package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.application.api.RetrievalGenerationIndexApi;
import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/** Ingestion-owned translation into Retrieval's published generation language. */
@Component
@RequiredArgsConstructor
public class IngestionRetrievalAcl {

    private final RetrievalGenerationIndexApi retrievalGenerationIndexApi;

    public RetrievalGenerationWriteReceipt replaceGeneration(
            IngestionTaskItem item,
            Asset asset,
            List<IngestionIndexSegment> segments) {
        long generation = requireGeneration(item);
        validateSegments(item, asset, generation, segments);
        List<RetrievalGenerationIndexRequest.SegmentValue> values = segments.stream()
                .map(this::toValue)
                .toList();
        return retrievalGenerationIndexApi.replaceGeneration(
                new RetrievalGenerationIndexRequest(
                        item.getKbId(), asset.getId(), generation, values));
    }

    private long requireGeneration(IngestionTaskItem item) {
        Long generation = item == null ? null : item.getTargetIndexGeneration();
        if (generation == null || generation < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return generation;
    }

    private void validateSegments(
            IngestionTaskItem item,
            Asset asset,
            long generation,
            List<IngestionIndexSegment> segments) {
        if (item == null
                || asset == null
                || !StringUtils.hasText(item.getKbId())
                || !StringUtils.hasText(asset.getId())
                || !item.getKbId().equals(asset.getKbId())) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Asset is required for indexing.");
        }
        if (segments == null) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Segments must not be null.");
        }
        if (segments.isEmpty() && !"image".equalsIgnoreCase(asset.getFileType())) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "No segments are available for indexing a non-image document.");
        }
        for (IngestionIndexSegment segment : segments) {
            if (segment == null
                    || !asset.getId().equals(segment.assetId())
                    || !asset.getKbId().equals(segment.kbId())
                    || segment.indexGeneration() != generation) {
                throw new BusinessException(
                        ApiError.INTERNAL_ERROR,
                        "Segment generation does not match the ingestion target.");
            }
        }
    }

    private RetrievalGenerationIndexRequest.SegmentValue toValue(IngestionIndexSegment source) {
        return new RetrievalGenerationIndexRequest.SegmentValue(
                source.segmentId(), source.kbId(), source.assetId(), source.indexGeneration(),
                source.assetType(), source.segmentType(), source.title(), source.contentText(),
                source.ocrText(), source.pageNo(), source.chunkOrder(), source.bbox(),
                source.imageWidth(), source.imageHeight(), source.embedding(), source.sourceRef(),
                source.thumbnail(), source.ocrSummary(), source.tags(), source.createdAt());
    }
}
