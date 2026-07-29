package com.anchr.core.search.domain.port;

/**
 * Domain port for object storage operations used by search.
 */
public interface SearchObjectStoragePort {

    record SignedObjectUrl(String url, long expiresAt) {
    }

    /**
     * Validity level for temporary AI input URLs.
     */
    enum AiInputValidity {
        SHORT,
        MEDIUM
    }

    /**
     * Build temporary AI input URL for image understanding and embedding.
     */
    String buildAiImageInput(String objectKey, AiInputValidity validity);

    /**
     * Build display URL for image browsing.
     */
    String buildDisplayImageUrl(String objectKey);

    /**
     * Build short-lived URL for preview loading.
     */
    SignedObjectUrl buildPreviewUrl(String objectKey);
}
