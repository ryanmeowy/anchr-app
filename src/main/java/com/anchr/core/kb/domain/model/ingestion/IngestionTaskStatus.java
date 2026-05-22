package com.anchr.core.kb.domain.model.ingestion;

/**
 * Status of an ingestion task.
 */
public enum IngestionTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED
}
