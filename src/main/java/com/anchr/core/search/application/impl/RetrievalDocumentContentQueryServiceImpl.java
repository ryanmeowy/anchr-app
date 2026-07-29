package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.RetrievalDocumentContentQueryApi;
import com.anchr.core.search.application.api.model.RetrievalDocumentChunk;
import com.anchr.core.search.application.api.model.RetrievalDocumentContentQuery;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** Retrieval-owned ordered content query implementation. */
@Service
@RequiredArgsConstructor
public class RetrievalDocumentContentQueryServiceImpl
        implements RetrievalDocumentContentQueryApi {

    private static final int MAX_PAGE_SIZE = 100;

    private final SegmentRepository segmentRepository;

    @Override
    public List<RetrievalDocumentChunk> query(RetrievalDocumentContentQuery query) {
        validate(query);
        return segmentRepository.listByAssetId(
                        query.kbId().trim(), query.assetId().trim(), query.generation(),
                        query.afterChunkOrder(), normalize(query.afterSegmentId()), query.limit())
                .stream()
                .map(this::toChunk)
                .toList();
    }

    private void validate(RetrievalDocumentContentQuery query) {
        if (query == null
                || !StringUtils.hasText(query.kbId())
                || !StringUtils.hasText(query.assetId())
                || query.generation() < 0L
                || query.limit() < 1
                || query.limit() > MAX_PAGE_SIZE
                || (query.afterChunkOrder() == null
                        && StringUtils.hasText(query.afterSegmentId()))) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST,
                    "kbId, assetId, generation and a valid ordered page are required.");
        }
    }

    private RetrievalDocumentChunk toChunk(Segment source) {
        return new RetrievalDocumentChunk(
                source.getSegmentId(), source.getKbId(), source.getAssetId(),
                source.getIndexGeneration(), source.getAssetType(),
                source.getSegmentType() == null ? null : source.getSegmentType().name(),
                source.getTitle(), content(source), source.getPageNo(), source.getChunkOrder(),
                source.getBbox(), source.getSourceRef());
    }

    private String content(Segment source) {
        if (StringUtils.hasText(source.getContentText())) return source.getContentText();
        if (StringUtils.hasText(source.getOcrText())) return source.getOcrText();
        return "";
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
