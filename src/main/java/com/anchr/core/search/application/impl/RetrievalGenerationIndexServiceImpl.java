package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.RetrievalGenerationIndexApi;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
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

    private static final int MAX_PROFILE_REEMBED_ATTEMPTS = 3;

    private final SegmentRepository segmentRepository;
    private final SearchSegmentBulkWriter segmentBulkWriter;
    private final SegmentIndexWriteBarrier indexWriteBarrier;
    private final SegmentIndexManager segmentIndexManager;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final SearchEmbeddingPort embeddingPort;
    private final SegmentIndexMigrationRunner migrationRunner;

    @Override
    public RetrievalGenerationWriteReceipt replaceGeneration(
            RetrievalGenerationIndexRequest request) {
        validateRequest(request);
        List<Segment> segments = request.segments().stream().map(this::toSegment).toList();
        PreparedWrite prepared = prepareForServingProfile(
                segments, request.embeddingProfileFingerprint());
        int reembedAttempts = prepared.reembedded() ? 1 : 0;
        SearchSegmentBulkWriter.WriteResult result;
        while (true) {
            PreparedWrite candidate = prepared;
            result = indexWriteBarrier.withWritePermit(() -> {
                if (!servingFingerprintMatches(candidate.profileFingerprint())) {
                    return null;
                }
                segmentRepository.deleteByAssetGeneration(
                        request.assetId(), request.generation());
                return segmentBulkWriter.write(candidate.segments());
            });
            if (result != null) {
                break;
            }
            if (reembedAttempts >= MAX_PROFILE_REEMBED_ATTEMPTS) {
                throw new BusinessException(
                        ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "Embedding profile changed too frequently during index write.");
            }
            prepared = reembedForActiveProfile(segments);
            reembedAttempts++;
        }
        return new RetrievalGenerationWriteReceipt(
                request.kbId(), request.assetId(), request.generation(), result.writtenCount(),
                result.indexName(), result.profileFingerprint());
    }

    private PreparedWrite prepareForServingProfile(
            List<Segment> segments,
            String requestFingerprint
    ) {
        if (segmentIndexManager == null
                || embeddingProfileProvider == null
                || embeddingPort == null
                || migrationRunner == null) {
            return new PreparedWrite(segments, requestFingerprint, false);
        }
        String servingFingerprint = segmentIndexManager.status()
                .getActualProfileFingerprint();
        if (!StringUtils.hasText(requestFingerprint)
                || requestFingerprint.equals(servingFingerprint)) {
            return new PreparedWrite(segments, servingFingerprint, false);
        }
        return reembedForActiveProfile(segments);
    }

    private PreparedWrite reembedForActiveProfile(List<Segment> segments) {
        EmbeddingProfile profile = embeddingProfileProvider.getActiveEmbeddingProfile()
                .orElseThrow(() -> new BusinessException(
                        ApiError.SEARCH_BACKEND_UNAVAILABLE,
                        "Embedding profile is unavailable during index write."));
        List<Segment> reprojected = migrationRunner.reprojectSegments(
                segments,
                profile,
                embeddingPort.openSession(profile));
        return new PreparedWrite(reprojected, profile.fingerprint(), true);
    }

    private boolean servingFingerprintMatches(String fingerprint) {
        if (segmentIndexManager == null || !StringUtils.hasText(fingerprint)) {
            return true;
        }
        return fingerprint.equals(
                segmentIndexManager.status().getActualProfileFingerprint());
    }

    private record PreparedWrite(
            List<Segment> segments,
            String profileFingerprint,
            boolean reembedded
    ) {
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
