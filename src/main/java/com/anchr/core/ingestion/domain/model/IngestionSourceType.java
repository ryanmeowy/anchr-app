package com.anchr.core.ingestion.domain.model;

/**
 * Source type for a knowledge base ingestion task.
 */
public enum IngestionSourceType {
    UPLOAD,
    URL,
    RETRY,
    REPARSE,
    REEMBED
}
