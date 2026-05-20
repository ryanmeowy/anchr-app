package com.anchr.core.search.domain.port;

import java.util.List;

/**
 * Domain port for reranking candidate documents.
 */
public interface SearchRerankPort {

    /**
     * Rerank candidate documents according to query relevance.
     *
     * @param query user query
     * @param documents candidate document texts
     * @param topN number of top results expected
     * @return sorted rerank items
     */
    List<RerankItem> rerank(String query, List<String> documents, Integer topN);

    /**
     * Rerank result item in domain language.
     */
    record RerankItem(int index, double score) {}
}
