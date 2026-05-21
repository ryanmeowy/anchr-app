package com.anchr.core.kb.domain.model.ingestion;

/**
 * Duplicate handling result for an ingestion item.
 */
public enum DedupeResult {
    NEW,
    SKIPPED,
    OVERWRITTEN,
    VERSIONED
}
