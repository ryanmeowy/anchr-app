package com.anchr.core.ingestion.application.model;

import com.anchr.core.common.model.ParseResponse;

import java.util.Locale;

/** Ingestion-owned snapshot of one Docling job. */
public record IngestionDoclingJob(
        String jobId,
        String requestId,
        String status,
        ParseResponse result,
        IngestionDoclingJobError error
) {

    public String normalizedStatus() {
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }
}
