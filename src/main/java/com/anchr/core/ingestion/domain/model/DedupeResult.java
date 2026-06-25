package com.anchr.core.ingestion.domain.model;

/**
 * Duplicate handling result for an ingestion item.
 */
public enum DedupeResult {
    NEW,
    SKIPPED,
    OVERWRITTEN,
    VERSIONED
}
