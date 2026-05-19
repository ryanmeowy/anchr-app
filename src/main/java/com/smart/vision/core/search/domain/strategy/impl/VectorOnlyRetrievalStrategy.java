package com.smart.vision.core.search.domain.strategy.impl;

import com.smart.vision.core.search.domain.model.ImageSearchResultDTO;
import com.smart.vision.core.search.domain.model.StrategyTypeEnum;
import com.smart.vision.core.search.domain.repository.ImageSearchRepository;
import com.smart.vision.core.search.domain.strategy.RetrievalStrategy;
import com.smart.vision.core.search.interfaces.rest.dto.SearchQueryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.smart.vision.core.common.constant.EmbeddingConstant.DEFAULT_TOP_K;

/**
 * Pure vector retrieval strategy (KNN only).
 */
@Component
@RequiredArgsConstructor
public class VectorOnlyRetrievalStrategy implements RetrievalStrategy {

    private final ImageSearchRepository imageSearchRepository;

    @Override
    public List<ImageSearchResultDTO> search(SearchQueryDTO query, List<Float> queryVector) {
        Integer topK = query == null || query.getTopK() == null ? DEFAULT_TOP_K : query.getTopK();
        return imageSearchRepository.vectorSearch(queryVector, topK);
    }

    @Override
    public StrategyTypeEnum getType() {
        return StrategyTypeEnum.VECTOR_ONLY;
    }
}
