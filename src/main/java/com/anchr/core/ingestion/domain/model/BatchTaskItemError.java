package com.anchr.core.ingestion.domain.model;

/**
 * Stable error messages for batch task items.
 */
public enum BatchTaskItemError {
    UNSUPPORTED_FILE_TYPE("File type not supported"),
    DATABASE_WRITE_FAILED("Database writes failed"),
    KB_SEGMENT_WRITE_FAILED("kb_segment writes failed"),
    PROCESS_FAILED("Processing failed");

    private final String message;

    BatchTaskItemError(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public String resolveMessage(String detail) {
        return detail == null || detail.isBlank() ? message : detail;
    }
}
