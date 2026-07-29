package com.anchr.core.ingestion.application.model;

/** Failure categories used by the ingestion retry policy. */
public enum IngestionDoclingFailureKind {
    TRANSIENT,
    NOT_FOUND,
    CONFLICT,
    CONFIGURATION,
    PERMANENT
}
