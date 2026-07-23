package com.anchr.core.ingestion.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionPublicProjectionPolicyTest {

    @Test
    void pending_shouldPreserveEverySourceCompatibilityProjection() {
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.UPLOAD),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.URL),
                IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, 10);
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
    void intakePending_shouldPreserveLegacyGenericEndpointProjection() {
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(IngestionSourceType.UPLOAD),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(IngestionSourceType.URL),
                IngestionStage.PARSE, IngestionTaskItemStatus.PENDING, 10);
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(IngestionSourceType.REPARSE),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(IngestionSourceType.REEMBED),
                IngestionStage.UPLOAD, IngestionTaskItemStatus.PENDING, 0);
        assertProjection(
                IngestionPublicProjectionPolicy.intakePending(IngestionSourceType.RETRY),
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
    void running_shouldMapRealExecutionPhasesAndNeverRegressProgress() {
        assertProjection(
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.PARSE_SUBMIT, 0),
                IngestionStage.PARSE, IngestionTaskItemStatus.RUNNING, 20);
        assertProjection(
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.PARSE_WAIT, 35),
                IngestionStage.PARSE, IngestionTaskItemStatus.RUNNING, 35);
        assertProjection(
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.PARSE_PERSIST, 10),
                IngestionStage.PARSE, IngestionTaskItemStatus.RUNNING, 20);
        assertProjection(
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.EMBED, 60),
                IngestionStage.EMBED, IngestionTaskItemStatus.RUNNING, 60);
        assertProjection(
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.INDEX, 55),
                IngestionStage.INDEX, IngestionTaskItemStatus.RUNNING, 75);
    }

    @Test
    void failed_shouldKeepTheFailedPhaseAndNeverRegressProgress() {
        assertProjection(
                IngestionPublicProjectionPolicy.failed(
                        IngestionExecutionStage.PARSE_WAIT, 10),
                IngestionStage.PARSE, IngestionTaskItemStatus.FAILED, 20);
        assertProjection(
                IngestionPublicProjectionPolicy.failed(
                        IngestionExecutionStage.EMBED, 60),
                IngestionStage.EMBED, IngestionTaskItemStatus.FAILED, 60);
        assertProjection(
                IngestionPublicProjectionPolicy.failed(
                        IngestionExecutionStage.INDEX, 90),
                IngestionStage.INDEX, IngestionTaskItemStatus.FAILED, 90);
    }

    @Test
    void transition_shouldDeriveStatusAndStageFromExecutionOutcome() {
        assertProjection(
                IngestionPublicProjectionPolicy.transition(
                        IngestionExecutionStage.PARSE_WAIT,
                        IngestionExecutionStage.EMBED,
                        35),
                IngestionStage.EMBED, IngestionTaskItemStatus.RUNNING, 55);
        assertProjection(
                IngestionPublicProjectionPolicy.transition(
                        IngestionExecutionStage.EMBED,
                        IngestionExecutionStage.FAILED,
                        60),
                IngestionStage.EMBED, IngestionTaskItemStatus.FAILED, 60);
        assertThat(IngestionPublicProjectionPolicy.transition(
                IngestionExecutionStage.INDEX,
                IngestionExecutionStage.COMPLETE,
                75)).isEqualTo(IngestionPublicProjectionPolicy.success());
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

    @Test
    void activeOutcomes_shouldRejectTerminalExecutionPhases() {
        assertThatThrownBy(() -> IngestionPublicProjectionPolicy.running(
                IngestionExecutionStage.COMPLETE, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IngestionPublicProjectionPolicy.running(
                IngestionExecutionStage.FAILED, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IngestionPublicProjectionPolicy.failed(
                IngestionExecutionStage.COMPLETE, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IngestionPublicProjectionPolicy.failed(
                IngestionExecutionStage.FAILED, 20))
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
