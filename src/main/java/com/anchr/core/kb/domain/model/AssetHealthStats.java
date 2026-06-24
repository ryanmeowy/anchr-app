package com.anchr.core.kb.domain.model;

/**
 * Aggregated document/segment ingestion stats for a single knowledge base,
 * used by the KB health report.
 */
public record AssetHealthStats(int documentTotal,
                               int documentIndexed,
                               int documentPending,
                               int documentFailed,
                               int segmentTotal,
                               int segmentIndexed) {
}
