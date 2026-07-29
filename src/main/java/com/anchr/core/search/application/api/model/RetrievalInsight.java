package com.anchr.core.search.application.api.model;

public record RetrievalInsight(
        Pipeline pipeline,
        RelevanceDistribution relevanceDistribution,
        Risk risk,
        HitSourceDistribution hitSourceDistribution,
        long latencyMs
) {
    public record Pipeline(
            int keywordCandidates,
            int vectorCandidates,
            int fusedRetained,
            int rerankAdopted
    ) {
    }

    public record RelevanceDistribution(int high, int medium, int low) {
    }

    public record Risk(int lowRelevanceCount) {
    }

    public record HitSourceDistribution(
            int vectorCount,
            int contentCount,
            int ocrCount,
            int tagCount,
            int titleCount
    ) {
    }
}
