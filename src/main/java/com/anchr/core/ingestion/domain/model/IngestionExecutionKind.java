package com.anchr.core.ingestion.domain.model;

/**
 * Business intent of one ingestion execution.
 */
public enum IngestionExecutionKind {
    INITIAL,
    REPARSE,
    REEMBED,
    EXPLICIT_RETRY
}
