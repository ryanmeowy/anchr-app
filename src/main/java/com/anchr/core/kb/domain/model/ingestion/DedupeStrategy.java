package com.anchr.core.kb.domain.model.ingestion;

/**
 * Duplicate document handling strategy.
 */
public enum DedupeStrategy {
    SKIP,
    OVERWRITE,
    VERSIONED
}
