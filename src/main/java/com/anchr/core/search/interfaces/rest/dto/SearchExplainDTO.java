package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Structured explanation of why a search result was matched.
 */
@Data
@Builder
public class SearchExplainDTO implements Serializable {

    /**
     * Effective retrieval strategy code, e.g. 0/1/2/3.
     */
    private String strategyEffective;

    /**
     * Coarse match sources for UI badge display: VECTOR/FILENAME/OCR/TAG/GRAPH.
     */
    private List<String> hitSources;

    /**
     * Fine-grained field match flags.
     */
    private MatchedBy matchedBy;

    @Data
    @Builder
    public static class MatchedBy implements Serializable {
        private boolean vector;
        private boolean filename;
        private boolean ocr;
        private boolean tag;
        private boolean graph;
    }
}
