package com.anchr.core.ingestion.application.model;

import java.time.Duration;

/** Ingestion-owned exception carrying Docling retry facts. */
public class IngestionDoclingException extends RuntimeException {

    private final IngestionDoclingFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;

    public IngestionDoclingException(
            IngestionDoclingFailureKind kind,
            Integer statusCode,
            Duration retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public IngestionDoclingFailureKind kind() {
        return kind;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
