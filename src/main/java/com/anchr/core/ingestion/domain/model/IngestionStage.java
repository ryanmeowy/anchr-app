package com.anchr.core.ingestion.domain.model;

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
