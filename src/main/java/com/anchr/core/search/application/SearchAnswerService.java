package com.anchr.core.search.application;

import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.SearchAnswerRequest;
import com.anchr.core.search.application.api.model.SearchAnswerResult;

import java.util.List;

/**
 * Builds grounded answers for search results.
 */
public interface SearchAnswerService {

    SearchAnswerResult answer(SearchAnswerRequest request, List<RetrievalHit> existingResults);
}
