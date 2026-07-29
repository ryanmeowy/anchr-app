package com.anchr.core.kb.domain.port;

/** Object-storage operations owned by Knowledge Content use cases. */
public interface KnowledgeObjectStoragePort {

    record SignedObjectUrl(String url, long expiresAt) {
    }

    SignedObjectUrl signPreviewUrl(String objectKey);

    /** Idempotently remove every owned object below an exact, validated prefix. */
    void deleteObjectsByPrefix(String prefix);
}
