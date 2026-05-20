package com.anchr.core.search.domain.port;

import org.springframework.web.multipart.MultipartFile;

/**
 * Domain port for object storage operations used by search.
 */
public interface SearchObjectStoragePort {

    /**
     * Validity level for temporary AI input URLs.
     */
    enum AiInputValidity {
        SHORT,
        MEDIUM
    }

    /**
     * Upload image file and return object key.
     */
    String uploadFile(MultipartFile file);

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
    String buildPreviewUrl(String objectKey);
}
