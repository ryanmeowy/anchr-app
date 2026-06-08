package com.anchr.core.ingestion.domain.model;

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
