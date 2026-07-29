package com.anchr.core.ingestion.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionPublicProjectionPolicyTest {

    @Test
    void pending_shouldPreserveEverySourceProjection() {
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.UPLOAD),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.REPARSE),
                IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, 20);
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.REEMBED),
                IngestionStage.EMBED, IngestionTaskItemStatus.PENDING, 60);
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.RETRY),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertThat(IngestionPublicProjectionPolicy.explicitRetry())
                .isEqualTo(IngestionPublicProjectionPolicy.pending(IngestionSourceType.RETRY));
    }

    @Test
    void intakePending_shouldUseUploadProjection() {
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
    }

    @Test
    void terminalCreateOutcomes_shouldUseFixedCompatibilityProjections() {
        assertProjection(
                IngestionPublicProjectionPolicy.preflightFailure(),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.FAILED, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.skipped(),
                IngestionStage.ASKABLE, IngestionTaskItemStatus.SKIPPED, 100);
        assertProjection(
                IngestionPublicProjectionPolicy.success(),
                IngestionStage.ASKABLE, IngestionTaskItemStatus.SUCCESS, 100);
    }

    @Test
    void projection_shouldRejectProgressOutsidePublicContract() {
        assertThatThrownBy(() -> new IngestionPublicProjection(
                IngestionStage.PARSE, IngestionTaskItemStatus.RUNNING, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionPublicProjection(
                IngestionStage.INDEX, IngestionTaskItemStatus.RUNNING, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertProjection(IngestionPublicProjection projection,
                                  IngestionStage stage,
                                  IngestionTaskItemStatus status,
                                  int progress) {
        assertThat(projection.stage()).isEqualTo(stage);
        assertThat(projection.status()).isEqualTo(status);
        assertThat(projection.progress()).isEqualTo(progress);
    }
}
