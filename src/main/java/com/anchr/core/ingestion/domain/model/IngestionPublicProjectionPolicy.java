package com.anchr.core.ingestion.domain.model;

import java.util.Objects;

/**
 * Single policy for the public ingestion item projection.
 */
public final class IngestionPublicProjectionPolicy {

    private static final int UPLOAD_PROGRESS = 0;
    private static final int URL_PARSE_PROGRESS = 10;
    private static final int PARSE_PROGRESS = 20;
    private static final int EMBED_PROGRESS = 55;
    private static final int REEMBED_PROGRESS = 60;
    private static final int INDEX_PROGRESS = 75;
    private static final int COMPLETE_PROGRESS = 100;

    private IngestionPublicProjectionPolicy() {
    }

    public static IngestionPublicProjection pending(IngestionSourceType sourceType) {
        Objects.requireNonNull(sourceType, "sourceType");
        return switch (sourceType) {
            case UPLOAD, RETRY -> projection(
                    IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, UPLOAD_PROGRESS);
            case URL -> projection(
                    IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, URL_PARSE_PROGRESS);
            case REPARSE -> projection(
                    IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, PARSE_PROGRESS);
            case REEMBED -> projection(
                    IngestionStage.EMBED, IngestionTaskItemStatus.PENDING, REEMBED_PROGRESS);
        };
    }

    /**
     * Compatibility projection for the generic create-task endpoint.
     *
     * <p>Only URL intake historically starts at parse progress. Maintenance
     * source values are accepted by that legacy endpoint as ordinary uploaded
     * items, so their specialized projections are reserved for the dedicated
     * reparse/reembed commands.</p>
     */
    public static IngestionPublicProjection intakePending(IngestionSourceType sourceType) {
        Objects.requireNonNull(sourceType, "sourceType");
        return sourceType == IngestionSourceType.URL
                ? pending(IngestionSourceType.URL)
                : pending(IngestionSourceType.UPLOAD);
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

    public static IngestionPublicProjection running(
            IngestionExecutionStage phase,
            int currentProgress) {
        return phaseProjection(phase, IngestionTaskItemStatus.RUNNING, currentProgress);
    }

    public static IngestionPublicProjection failed(
            IngestionExecutionStage phase,
            int currentProgress) {
        return phaseProjection(phase, IngestionTaskItemStatus.FAILED, currentProgress);
    }

    public static IngestionPublicProjection success() {
        return projection(
                IngestionStage.ASKABLE, IngestionTaskItemStatus.SUCCESS, COMPLETE_PROGRESS);
    }

    /**
     * Canonical public projection for a fenced execution transition.
     */
    public static IngestionPublicProjection transition(
            IngestionExecutionStage currentPhase,
            IngestionExecutionStage nextPhase,
            int progress) {
        Objects.requireNonNull(currentPhase, "currentPhase");
        Objects.requireNonNull(nextPhase, "nextPhase");
        return switch (nextPhase) {
            case COMPLETE -> success();
            case FAILED -> failed(currentPhase, progress);
            case PARSE_SUBMIT, PARSE_WAIT, PARSE_PERSIST, EMBED, INDEX ->
                    running(nextPhase, progress);
        };
    }

    private static IngestionPublicProjection phaseProjection(
            IngestionExecutionStage phase,
            IngestionTaskItemStatus status,
            int currentProgress) {
        Objects.requireNonNull(phase, "phase");
        return switch (phase) {
            case PARSE_SUBMIT, PARSE_WAIT, PARSE_PERSIST -> projection(
                    IngestionStage.PARSE, status, Math.max(currentProgress, PARSE_PROGRESS));
            case EMBED -> projection(
                    IngestionStage.EMBED, status, Math.max(currentProgress, EMBED_PROGRESS));
            case INDEX -> projection(
                    IngestionStage.INDEX, status, Math.max(currentProgress, INDEX_PROGRESS));
            case COMPLETE, FAILED -> throw new IllegalArgumentException(
                    "Terminal execution phase has no active public projection: " + phase);
        };
    }

    private static IngestionPublicProjection projection(
            IngestionStage stage,
            IngestionTaskItemStatus status,
            int progress) {
        return new IngestionPublicProjection(stage, status, progress);
    }
}
