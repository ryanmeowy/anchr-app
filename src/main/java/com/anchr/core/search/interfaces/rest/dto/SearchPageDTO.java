package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Paged kb search response.
 */
@Data
@Builder
public class SearchPageDTO implements Serializable {

    private List<SearchResultDTO> items;
    private long total;
    private String nextCursor;
    private Map<String, List<FacetItemDTO>> facets;
    private SearchAnswerDTO answer;
    private List<String> rewrittenKeywords;

    @Data
    @Builder
    public static class FacetItemDTO implements Serializable {
        private String value;
        private long count;
    }
}
