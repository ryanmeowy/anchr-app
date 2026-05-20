package com.anchr.core.ingestion.domain.model;

/**
 * Batch task lifecycle status.
 */
public enum BatchTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_FAILED,
    FAILED
}

