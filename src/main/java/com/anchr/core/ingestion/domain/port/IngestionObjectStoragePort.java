package com.anchr.core.ingestion.domain.port;

import java.util.Optional;

/**
 * Domain port for object storage operations used by ingestion.
 */
public interface IngestionObjectStoragePort {

    record ObjectStat(long size, String contentType) {
    }

    /**
     * Read the authoritative size and content type of a source object.
     *
     * @param objectKey object storage key
     * @return metadata when the object exists
     */
    Optional<ObjectStat> statObject(String objectKey);

    /**
     * Build temporary download url for original object content.
     *
     * @param objectKey object storage key
     * @return temporary accessible download url
     */
    String buildDownloadUrl(String objectKey);

    /**
     * Build a temporary URL for a compressed derivative used only as image embedding input.
     *
     * <p>The stable source remains {@code objectKey}; implementations may apply provider-native
     * image processing while signing this URL.</p>
     *
     * @param objectKey original image object key
     * @return temporary accessible URL for the compressed image derivative
     */
    String buildImageEmbeddingUrl(String objectKey);

    /** Idempotently remove every owned object below an exact, validated prefix. */
    default void deleteObjectsByPrefix(String prefix) {
        throw new UnsupportedOperationException("Prefix deletion is not configured.");
    }

}
