package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.KbQueryEmbeddingService;
import com.smart.vision.core.search.domain.port.SearchEmbeddingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Default kb query embedding service.
 */
@Service
@RequiredArgsConstructor
public class KbQueryEmbeddingServiceImpl implements KbQueryEmbeddingService {

    private final SearchEmbeddingPort searchEmbeddingPort;

    @Override
    public List<Float> embedQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        List<Float> embedding = searchEmbeddingPort.embedText(query.trim());
        if (CollectionUtils.isEmpty(embedding)) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
        }
        return embedding;
    }
}
