package com.anchr.core.ingestion.application.artifact;

import lombok.Getter;

/**
 * Signals a failure while persisting or validating a durable ingestion-stage artifact.
 */
@Getter
public class IngestionArtifactException extends RuntimeException {

    private final Reason reason;

    public IngestionArtifactException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IngestionArtifactException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public boolean isRetryable() {
        return reason == Reason.STORAGE;
    }

    public enum Reason {
        STORAGE,
        TOO_LARGE,
        CORRUPT,
        IDENTITY_MISMATCH,
        IMMUTABLE_CONFLICT
    }
}
