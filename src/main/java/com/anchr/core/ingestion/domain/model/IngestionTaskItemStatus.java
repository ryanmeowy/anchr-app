package com.anchr.core.ingestion.domain.model;

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
