package com.smart.vision.core.search.application;

import com.smart.vision.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchResultDTO;

import java.util.List;

/**
 * Unified search service for text + image segment retrieval.
 */
public interface UnifiedSearchService {

    List<KbSearchResultDTO> search(KbSearchQueryDTO query);
}
