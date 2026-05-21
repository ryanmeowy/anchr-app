package com.anchr.core.kb.domain.model.ingestion;

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
