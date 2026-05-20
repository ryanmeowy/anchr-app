package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchResultDTO;

import java.util.List;

/**
 * Unified search service for text + image segment retrieval.
 */
public interface UnifiedSearchService {

    List<KbSearchResultDTO> search(KbSearchQueryDTO query);
}
