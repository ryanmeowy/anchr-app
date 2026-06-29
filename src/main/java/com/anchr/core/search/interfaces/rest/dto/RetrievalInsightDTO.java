package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Retrieval insight diagnostics for the search response.
 * Provides pipeline counts, relevance distribution, risk indicators,
 * hit source breakdown, query intent, and latency.
 */
@Data
@Builder
public class RetrievalInsightDTO implements Serializable {

    private PipelineDTO pipeline;
    private RelevanceDistributionDTO relevanceDistribution;
    private RiskDTO risk;
    private HitSourceDistributionDTO hitSourceDistribution;
    private QueryIntentDTO queryIntent;
    private long latencyMs;

    @Data
    @Builder
    public static class PipelineDTO implements Serializable {
        private int keywordCandidates;
        private int vectorCandidates;
        private int fusedRetained;
        private int rerankAdopted;
    }

    @Data
    @Builder
    public static class RelevanceDistributionDTO implements Serializable {
        private int high;
        private int medium;
        private int low;
    }

    @Data
    @Builder
    public static class RiskDTO implements Serializable {
        private int lowRelevanceCount;
    }

    @Data
    @Builder
    public static class HitSourceDistributionDTO implements Serializable {
        private int vectorCount;
        private int contentCount;
        private int ocrCount;
        private int tagCount;
        private int titleCount;
    }

    @Data
    @Builder
    public static class QueryIntentDTO implements Serializable {
        private String intent;
        private String category;
        private boolean fallback;
    }
}
