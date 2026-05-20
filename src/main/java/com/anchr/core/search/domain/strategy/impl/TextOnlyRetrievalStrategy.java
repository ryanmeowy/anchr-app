package com.anchr.core.search.domain.strategy.impl;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;
import com.anchr.core.search.domain.repository.ImageSearchRepository;
import com.anchr.core.search.domain.strategy.RetrievalStrategy;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.anchr.core.common.constant.SearchConstant.DEFAULT_RESULT_LIMIT;

/**
 * Text-only retrieval strategy (BM25 style keyword search).
 */
@Component
@RequiredArgsConstructor
public class TextOnlyRetrievalStrategy implements RetrievalStrategy {

    private final ImageSearchRepository imageSearchRepository;

    @Override
    public List<ImageSearchResultDTO> search(SearchQueryDTO query, List<Float> queryVector) {
        if (query == null) {
            return List.of();
        }
        Integer limit = query.getLimit() == null ? DEFAULT_RESULT_LIMIT : query.getLimit();
        Boolean enableOcr = query.getEnableOcr() == null ? Boolean.TRUE : query.getEnableOcr();
        return imageSearchRepository.textSearch(query.getKeyword(), limit, enableOcr);
    }

    @Override
    public StrategyTypeEnum getType() {
        return StrategyTypeEnum.TEXT_ONLY;
    }
}
