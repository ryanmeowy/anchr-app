package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.SearchAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;

/**
 * Builds grounded answers for search results.
 */
public interface SearchAnswerService {

    SearchAnswerDTO answer(SearchQueryDTO query);
}
