package com.anchr.core.ingestion.domain.port;

/**
 * Domain port for object storage operations used by ingestion.
 */
public interface IngestionObjectStoragePort {

    /**
     * Build temporary download url for original object content.
     *
     * @param objectKey object storage key
     * @return temporary accessible download url
     */
    String buildDownloadUrl(String objectKey);

    /**
     * Build temporary image input for AI embedding/OCR/captioning.
     *
     * @param objectKey object storage key
     * @return temporary accessible image input
     */
    String buildAiImageInput(String objectKey);
}
