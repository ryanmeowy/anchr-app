package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
final class SegmentIndexStatusAssembler {

    SegmentIndexStatusDTO assemble(
            SegmentIndexLifecycleState state,
            EmbeddingProfile expectedProfile
    ) {
        return SegmentIndexStatusDTO.builder()
                .status(state.status())
                .indexExists(Boolean.TRUE.equals(state.indexExists()))
                .readable(state.readable())
                .writable(state.writable())
                .actualDim(state.actualDim())
                .actualModel(state.actualModel())
                .actualProfileFingerprint(state.actualProfileFingerprint())
                .expectedDim(expectedProfile == null ? null : expectedProfile.dimension())
                .expectedModel(expectedProfile == null ? null : expectedProfile.modelName())
                .expectedProfileFingerprint(
                        expectedProfile == null ? null : expectedProfile.fingerprint())
                .pendingRebuild(toPendingRebuildDto(state.pendingRebuild()))
                .rebuildProgress(toRebuildProgressDto(state.rebuildProgress()))
                .lastError(state.lastError())
                .build();
    }

    String buildRebuildReason(SegmentIndexStatusDTO status) {
        boolean dimChanged = !Objects.equals(
                status.getActualDim(), status.getExpectedDim());
        boolean profileChanged = !Objects.equals(
                status.getActualProfileFingerprint(),
                status.getExpectedProfileFingerprint());
        boolean modelNameChanged = !Objects.equals(
                status.getActualModel(), status.getExpectedModel());
        if (dimChanged && profileChanged) {
            String modelText = modelNameChanged
                    ? "，模型 " + status.getActualModel()
                            + " -> " + status.getExpectedModel()
                    : "，模型 " + status.getExpectedModel();
            return "Embedding 配置已变化：维度 "
                    + status.getActualDim() + " -> " + status.getExpectedDim()
                    + modelText;
        }
        if (dimChanged) {
            return "Embedding 维度已变化："
                    + status.getActualDim() + " -> " + status.getExpectedDim();
        }
        if (modelNameChanged) {
            return "Embedding 模型已变化："
                    + status.getActualModel() + " -> " + status.getExpectedModel();
        }
        return "Embedding 配置已变化：模型 " + status.getExpectedModel();
    }

    private SegmentIndexStatusDTO.PendingRebuild toPendingRebuildDto(
            SegmentIndexPendingRebuild pending
    ) {
        if (pending == null) {
            return null;
        }
        return SegmentIndexStatusDTO.PendingRebuild.builder()
                .taskId(pending.taskId())
                .expectedDim(pending.targetProfile().dimension())
                .reason(pending.reason())
                .createdAt(pending.createdAt())
                .build();
    }

    private SegmentIndexStatusDTO.RebuildProgress toRebuildProgressDto(
            SegmentIndexRebuildProgress progress
    ) {
        if (progress == null) {
            return null;
        }
        return SegmentIndexStatusDTO.RebuildProgress.builder()
                .migrated(progress.migrated())
                .total(progress.total())
                .phase(progress.phase())
                .dirtyAssets(progress.dirtyAssets())
                .build();
    }
}
