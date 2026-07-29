package com.anchr.core.ingestion.application.model;

import org.springframework.util.StringUtils;

/** Stable object-storage output target captured for one ingestion run. */
public record IngestionStorageTarget(
        String endpoint,
        String bucket,
        String basePath,
        String objectKeyLayout
) {

    public IngestionStorageTarget {
        requireText(endpoint, "OSS endpoint");
        requireText(bucket, "OSS bucket");
        if (basePath == null) {
            throw new IllegalStateException("OSS base path is missing from the request template.");
        }
        requireText(objectKeyLayout, "OSS object key layout");
    }

    private static void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(fieldName + " must not be blank.");
        }
    }
}
