package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.RetrievalGenerationIndexApi;
import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.infrastructure.persistence.es.SearchSegmentBulkWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** Retrieval-owned generation replacement implementation. */
@Service
@RequiredArgsConstructor
public class RetrievalGenerationIndexServiceImpl implements RetrievalGenerationIndexApi {

    private final SegmentRepository segmentRepository;
    private final SearchSegmentBulkWriter segmentBulkWriter;

    @Override
    public RetrievalGenerationWriteReceipt replaceGeneration(
            RetrievalGenerationIndexRequest request) {
        validateRequest(request);
        List<Segment> segments = request.segments().stream().map(this::toSegment).toList();
        segmentRepository.deleteByAssetGeneration(request.assetId(), request.generation());
        SearchSegmentBulkWriter.WriteResult result = segmentBulkWriter.write(segments);
        return new RetrievalGenerationWriteReceipt(
                request.kbId(), request.assetId(), request.generation(), result.writtenCount(),
                result.indexName(), result.profileFingerprint());
    }

    private void validateRequest(RetrievalGenerationIndexRequest request) {
        if (request == null
                || !StringUtils.hasText(request.kbId())
                || !StringUtils.hasText(request.assetId())
                || request.generation() < 1L) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST,
                    "kbId, assetId and a positive generation are required.");
        }
        for (RetrievalGenerationIndexRequest.SegmentValue segment : request.segments()) {
            if (segment == null
                    || !request.kbId().equals(segment.kbId())
                    || !request.assetId().equals(segment.assetId())
                    || request.generation() != segment.indexGeneration()
                    || !StringUtils.hasText(segment.segmentId())
                    || !StringUtils.hasText(segment.segmentType())) {
                throw new BusinessException(
                        ApiError.INVALID_REQUEST,
                        "Segment identity does not match the generation request.");
            }
        }
    }

    private Segment toSegment(RetrievalGenerationIndexRequest.SegmentValue source) {
        SegmentType segmentType;
        try {
            segmentType = SegmentType.valueOf(source.segmentType().trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST,
                    "Unsupported segment type: " + source.segmentType(), exception);
        }
        return Segment.builder()
                .segmentId(source.segmentId())
                .kbId(source.kbId())
                .assetId(source.assetId())
                .indexGeneration(source.indexGeneration())
                .assetType(source.assetType())
                .segmentType(segmentType)
                .title(source.title())
                .contentText(source.contentText())
                .ocrText(source.ocrText())
                .pageNo(source.pageNo())
                .chunkOrder(source.chunkOrder())
                .bbox(source.bbox())
                .imageWidth(source.imageWidth())
                .imageHeight(source.imageHeight())
                .embedding(source.embedding())
                .sourceRef(source.sourceRef())
                .thumbnail(source.thumbnail())
                .ocrSummary(source.ocrSummary())
                .tags(source.tags())
                .createdAt(source.createdAt())
                .build();
    }
}
