package com.anchr.core.ingestion.domain.model;

/**
 * Signals that a failed item changed after its retry execution was prepared.
 *
 * <p>This exception is unchecked so the repository transaction rolls back the
 * newly inserted parse attempt and execution.</p>
 */
public class IngestionRetryConflictException extends RuntimeException {

    public IngestionRetryConflictException(String message) {
        super(message);
    }
}
