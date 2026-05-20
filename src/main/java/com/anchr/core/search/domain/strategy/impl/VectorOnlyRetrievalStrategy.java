package com.anchr.core.search.domain.strategy.impl;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;
import com.anchr.core.search.domain.repository.ImageSearchRepository;
import com.anchr.core.search.domain.strategy.RetrievalStrategy;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_TOP_K;

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
