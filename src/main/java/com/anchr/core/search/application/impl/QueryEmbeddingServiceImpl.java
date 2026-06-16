package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
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
public class QueryEmbeddingServiceImpl implements QueryEmbeddingService {

    private final SearchEmbeddingPort searchEmbeddingPort;

    @Override
    public List<Float> embedQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        List<Float> embedding = searchEmbeddingPort.embed(query.trim(), "text");
        if (CollectionUtils.isEmpty(embedding)) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
        }
        return embedding;
    }
}
