package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;

import java.util.List;

/**
 * Unified search service for text + image segment retrieval.
 */
public interface UnifiedSearchService {

    List<SearchResultDTO> search(SearchQueryDTO query);

    List<SearchResultDTO> search(SearchQueryDTO query, List<String> keywords);

    SearchPageDTO searchPage(SearchQueryDTO query, List<String> keywords);
}
