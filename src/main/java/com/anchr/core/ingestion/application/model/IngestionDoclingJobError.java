package com.anchr.core.ingestion.application.model;

/** Ingestion-owned Docling failure payload. */
public record IngestionDoclingJobError(String code, String message) {
}
