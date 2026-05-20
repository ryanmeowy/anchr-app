package com.anchr.core.search.domain.strategy;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;

import java.util.List;

/**
 * Search strategy interface that defines the contract for different retrieval approaches
 * in the Anchr system. Each implementation represents a specific search strategy
 * such as hybrid search, vector-only search, text-only search, or image-to-image search.
 *
 * @author Ryan
 * @since 2025/12/15
 */
public interface RetrievalStrategy {
    /**
     * Execute search operation using the specific strategy implementation
     * This method performs the actual search logic based on the provided query parameters
     * and optional vector representation of the user's search query.
     *
     * @param query       search request
     * @param queryVector vector representation of user query (maybe null)
     * @return list of matched documents
     */
    List<ImageSearchResultDTO> search(SearchQueryDTO query, List<Float> queryVector);

    /**
     * Get the strategy type identifier that this implementation represents
     * This method allows the strategy factory or manager to identify and select
     * the appropriate strategy implementation based on the requested search type.
     *
     *
     * @return strategy type
     */
    StrategyTypeEnum getType();
}
