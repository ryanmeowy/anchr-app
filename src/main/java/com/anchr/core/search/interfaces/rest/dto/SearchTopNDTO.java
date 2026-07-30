package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Bounded Top-N kb search response.
 */
@Data
@Builder
public class SearchTopNDTO implements Serializable {

    private List<SearchResultDTO> items;
    private long returnedCount;
    private Map<String, List<FacetItemDTO>> windowFacets;
    private SearchAnswerDTO answer;
    private String rewrittenQuery;
    private List<String> rewrittenKeywords;
    private RetrievalInsightDTO insight;
    private List<String> suggestedQuestions;

    @Data
    @Builder
    public static class FacetItemDTO implements Serializable {
        private String value;
        private long count;
    }
}
