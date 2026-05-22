package com.anchr.core.kb.domain.model.ingestion;

/**
 * Stage of an ingestion task item.
 */
public enum IngestionStage {
    UPLOAD,
    PARSE,
    CHUNK,
    EMBED,
    INDEX,
    ASKABLE
}
