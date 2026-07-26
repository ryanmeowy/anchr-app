package com.anchr.core.search.domain.model;

/** Capability-loss report generated before a multimodal-to-text deployment is confirmed. */
public record EmbeddingImpactReport(
        long imageAssets,
        long ocrAvailableAssets,
        long ocrEmptyAssets,
        long textVectorFailures,
        long expectedVisualSemanticLossAssets,
        boolean confirmationRequired,
        boolean confirmed
) {
    public static EmbeddingImpactReport none() {
        return new EmbeddingImpactReport(0, 0, 0, 0, 0, false, true);
    }
}
