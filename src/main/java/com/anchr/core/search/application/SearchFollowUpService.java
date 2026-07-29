package com.anchr.core.search.application;

import com.anchr.core.search.application.api.model.RetrievalHit;

import java.util.List;

/**
 * Service for generating LLM-powered follow-up questions based on search results.
 */
public interface SearchFollowUpService {

    /**
     * Generate recommended follow-up questions.
     *
     * @param query   the user's original search query
     * @param results the top search results for context
     * @return up to 3 follow-up questions, empty list on failure
     */
    List<String> generate(String query, List<RetrievalHit> results);
}
