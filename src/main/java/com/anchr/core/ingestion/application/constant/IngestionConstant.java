package com.anchr.core.ingestion.application.constant;

/**
 * Fixed ingestion request limits shared by capability declarations and task validation.
 */
public final class IngestionConstant {

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1_024 * 1_024L;
    public static final long MAX_IMAGE_FILE_SIZE_BYTES = 5 * 1_024 * 1_024L;
    public static final int MAX_FILES_PER_BATCH = 20;

    private IngestionConstant() {
    }
}
