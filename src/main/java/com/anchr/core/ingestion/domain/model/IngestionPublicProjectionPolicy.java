package com.anchr.core.ingestion.domain.model;

import java.util.Objects;

/**
 * Single policy for the public ingestion item projection.
 */
public final class IngestionPublicProjectionPolicy {

    private static final int UPLOAD_PROGRESS = 0;
    private static final int PARSE_PROGRESS = 20;
    private static final int REEMBED_PROGRESS = 60;
    private static final int COMPLETE_PROGRESS = 100;

    private IngestionPublicProjectionPolicy() {
    }

    public static IngestionPublicProjection pending(IngestionSourceType sourceType) {
        Objects.requireNonNull(sourceType, "sourceType");
        return switch (sourceType) {
            case UPLOAD, RETRY -> projection(
                    IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, UPLOAD_PROGRESS);
            case REPARSE -> projection(
                    IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, PARSE_PROGRESS);
            case REEMBED -> projection(
                    IngestionStage.EMBED, IngestionTaskItemStatus.PENDING, REEMBED_PROGRESS);
        };
    }

    /** Initial projection for the public file-upload endpoint. */
    public static IngestionPublicProjection intakePending() {
        return pending(IngestionSourceType.UPLOAD);
    }

    public static IngestionPublicProjection explicitRetry() {
        return pending(IngestionSourceType.RETRY);
    }

    public static IngestionPublicProjection preflightFailure() {
        return projection(
                IngestionStage.UPLOAD, IngestionTaskItemStatus.FAILED, UPLOAD_PROGRESS);
    }

    public static IngestionPublicProjection skipped() {
        return projection(
                IngestionStage.ASKABLE, IngestionTaskItemStatus.SKIPPED, COMPLETE_PROGRESS);
    }

    public static IngestionPublicProjection success() {
        return projection(
                IngestionStage.ASKABLE, IngestionTaskItemStatus.SUCCESS, COMPLETE_PROGRESS);
    }

    private static IngestionPublicProjection projection(
            IngestionStage stage,
            IngestionTaskItemStatus status,
            int progress) {
        return new IngestionPublicProjection(stage, status, progress);
    }
}
