package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.SearchAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;

import java.util.List;

/**
 * Builds grounded answers for search results.
 */
public interface SearchAnswerService {

    SearchAnswerDTO answer(SearchQueryDTO query);

    SearchAnswerDTO answer(SearchQueryDTO query, List<SearchResultDTO> existingResults);
}
