package com.anchr.core.kb.domain.model.ingestion;

/**
 * Status of an ingestion task item.
 */
public enum IngestionTaskItemStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
