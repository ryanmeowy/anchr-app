package com.anchr.core.ingestion.domain.model;

/**
 * Duplicate document handling strategy.
 */
public enum DedupeStrategy {
    SKIP,
    OVERWRITE,
    VERSIONED
}
