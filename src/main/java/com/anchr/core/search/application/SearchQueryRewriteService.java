package com.anchr.core.search.application;

import com.anchr.core.search.application.model.SearchRewriteResult;

/**
 * Rewrites a standalone search query into concise keywords for improved retrieval.
 */
public interface SearchQueryRewriteService {

    SearchRewriteResult rewrite(String query);
}
