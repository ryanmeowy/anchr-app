package com.anchr.core.ingestion.domain.port;

import java.util.Optional;

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
     * Build a temporary URL for a compressed derivative used only as image embedding input.
     *
     * <p>The stable source remains {@code objectKey}; implementations may apply provider-native
     * image processing while signing this URL.</p>
     *
     * @param objectKey original image object key
     * @return temporary accessible URL for the compressed image derivative
     */
    String buildImageEmbeddingUrl(String objectKey);

    /**
     * Atomically persist an immutable ingestion artifact.
     *
     * <p>The implementation must use the storage provider's create-only/conditional
     * write primitive. It must never implement this contract with a
     * check-then-overwrite sequence.</p>
     *
     * @param objectKey object storage key
     * @param content artifact bytes
     * @param contentType media type
     * @param contentEncoding optional content encoding
     * @return {@code true} when this call created the object, or {@code false}
     *         when an object already exists at the same key
     */
    boolean putArtifactIfAbsent(String objectKey, byte[] content,
                                String contentType, String contentEncoding);

    /**
     * Read an ingestion artifact with an enforced upper bound.
     *
     * @param objectKey object storage key
     * @param maxBytes maximum number of bytes accepted from storage
     * @return artifact bytes
     */
    byte[] readArtifact(String objectKey, int maxBytes);

    /** Read an artifact for cleanup, returning empty only when the object does not exist. */
    default Optional<byte[]> readArtifactIfPresent(String objectKey, int maxBytes) {
        return Optional.of(readArtifact(objectKey, maxBytes));
    }

    /** Idempotently remove one owned object. */
    default void deleteObject(String objectKey) {
        throw new UnsupportedOperationException("Object deletion is not configured.");
    }

    /** Idempotently remove every owned object below an exact, validated prefix. */
    default void deleteObjectsByPrefix(String prefix) {
        throw new UnsupportedOperationException("Prefix deletion is not configured.");
    }

}
