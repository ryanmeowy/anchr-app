package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentIndexStatusAssemblerTest {
    private final SegmentIndexStatusAssembler assembler =
            new SegmentIndexStatusAssembler();

    @Test
    void shouldProjectLifecycleStateWithoutLosingPendingOrProgressFields() {
        EmbeddingProfile target = new EmbeddingProfile(
                2L, "EMBEDDING", "new-model", 1024, "new-fingerprint");
        SegmentIndexLifecycleState state = new SegmentIndexLifecycleState(
                SegmentIndexStatus.REBUILDING,
                "last error",
                new SegmentIndexPendingRebuild(
                        "task-1", target, "model changed", "2026-07-30T10:00:00"),
                new SegmentIndexRebuildProgress(40, 100, "BACKFILLING"),
                true,
                "kb_segment_old",
                true,
                false,
                768,
                "old-model",
                "old-fingerprint");

        SegmentIndexStatusDTO status = assembler.assemble(state, target);

        assertThat(status.getStatus()).isEqualTo(SegmentIndexStatus.REBUILDING);
        assertThat(status.isIndexExists()).isTrue();
        assertThat(status.isReadable()).isTrue();
        assertThat(status.isWritable()).isFalse();
        assertThat(status.getActualDim()).isEqualTo(768);
        assertThat(status.getActualModel()).isEqualTo("old-model");
        assertThat(status.getActualProfileFingerprint())
                .isEqualTo("old-fingerprint");
        assertThat(status.getExpectedDim()).isEqualTo(1024);
        assertThat(status.getExpectedModel()).isEqualTo("new-model");
        assertThat(status.getExpectedProfileFingerprint())
                .isEqualTo("new-fingerprint");
        assertThat(status.getPendingRebuild().getTaskId()).isEqualTo("task-1");
        assertThat(status.getPendingRebuild().getExpectedDim()).isEqualTo(1024);
        assertThat(status.getPendingRebuild().getReason()).isEqualTo("model changed");
        assertThat(status.getPendingRebuild().getCreatedAt())
                .isEqualTo("2026-07-30T10:00:00");
        assertThat(status.getRebuildProgress().getMigrated()).isEqualTo(40);
        assertThat(status.getRebuildProgress().getTotal()).isEqualTo(100);
        assertThat(status.getRebuildProgress().getPhase()).isEqualTo("BACKFILLING");
        assertThat(status.getLastError()).isEqualTo("last error");
    }

    @Test
    void shouldKeepExistingRebuildReasonWording() {
        assertThat(assembler.buildRebuildReason(status(
                768, "old-model", "old-fingerprint",
                1024, "new-model", "new-fingerprint")))
                .isEqualTo("Embedding 配置已变化：维度 768 -> 1024，模型 old-model -> new-model");
        assertThat(assembler.buildRebuildReason(status(
                768, "same-model", "old-fingerprint",
                1024, "same-model", "new-fingerprint")))
                .isEqualTo("Embedding 配置已变化：维度 768 -> 1024，模型 same-model");
        assertThat(assembler.buildRebuildReason(status(
                1024, "old-model", "old-fingerprint",
                1024, "new-model", "new-fingerprint")))
                .isEqualTo("Embedding 模型已变化：old-model -> new-model");
    }

    private SegmentIndexStatusDTO status(
            int actualDim,
            String actualModel,
            String actualFingerprint,
            int expectedDim,
            String expectedModel,
            String expectedFingerprint
    ) {
        return SegmentIndexStatusDTO.builder()
                .actualDim(actualDim)
                .actualModel(actualModel)
                .actualProfileFingerprint(actualFingerprint)
                .expectedDim(expectedDim)
                .expectedModel(expectedModel)
                .expectedProfileFingerprint(expectedFingerprint)
                .build();
    }
}
