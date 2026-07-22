package com.anchr.core.common.model;

/**
 * Request-specific disposition metadata for an API error response.
 *
 * <p>{@code requestAccepted == null} means the server cannot prove whether durable side effects
 * were committed. Upload cleanup is denied by default and must only be enabled when the request
 * is known to have been rejected before any durable object reference was created.</p>
 */
public record ErrorResponseMetadata(Boolean requestAccepted, boolean uploadCleanupAllowed) {

    public static ErrorResponseMetadata conservative() {
        return new ErrorResponseMetadata(null, false);
    }

    public static ErrorResponseMetadata rejectedWithUploadCleanup() {
        return new ErrorResponseMetadata(false, true);
    }
}
