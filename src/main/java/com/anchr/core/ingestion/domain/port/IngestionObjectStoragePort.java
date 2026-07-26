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

}
