package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentIndexStatus;

record SegmentIndexLifecycleState(
        SegmentIndexStatus status,
        String lastError,
        SegmentIndexPendingRebuild pendingRebuild,
        SegmentIndexRebuildProgress rebuildProgress,
        Boolean indexExists,
        String readIndex,
        boolean readable,
        boolean writable,
        Integer actualDim,
        String actualModel,
        String actualProfileFingerprint
) {
    static SegmentIndexLifecycleState initial() {
        return new SegmentIndexLifecycleState(
                SegmentIndexStatus.NOT_READY, null, null, null,
                null, null, false, false, null, null, null);
    }

    SegmentIndexLifecycleState withStatus(
            SegmentIndexStatus newStatus,
            String newLastError
    ) {
        return new SegmentIndexLifecycleState(
                newStatus, newLastError, pendingRebuild, rebuildProgress,
                indexExists, readIndex, readable, writable,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState withPendingRebuild(
            SegmentIndexPendingRebuild newPendingRebuild
    ) {
        return new SegmentIndexLifecycleState(
                status, lastError, newPendingRebuild, rebuildProgress,
                indexExists, readIndex, readable, writable,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState withoutPendingRebuild(String newLastError) {
        return new SegmentIndexLifecycleState(
                status, newLastError, null, null,
                indexExists, readIndex, readable, writable,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState withRebuildProgress(
            SegmentIndexRebuildProgress newRebuildProgress
    ) {
        return new SegmentIndexLifecycleState(
                status, lastError, pendingRebuild, newRebuildProgress,
                indexExists, readIndex, readable, writable,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState withLastError(String newLastError) {
        return new SegmentIndexLifecycleState(
                status, newLastError, pendingRebuild, rebuildProgress,
                indexExists, readIndex, readable, writable,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState withIndexInfo(
            boolean newIndexExists,
            String newReadIndex,
            boolean newReadable,
            boolean newWritable,
            Integer newActualDim,
            String newActualModel,
            String newActualProfileFingerprint
    ) {
        return new SegmentIndexLifecycleState(
                status, lastError, pendingRebuild, rebuildProgress,
                newIndexExists,
                newReadIndex,
                newReadable,
                status == SegmentIndexStatus.READY && newWritable,
                newActualDim,
                newActualModel,
                newActualProfileFingerprint);
    }

    SegmentIndexLifecycleState claimRebuild() {
        return new SegmentIndexLifecycleState(
                SegmentIndexStatus.REBUILDING, null, pendingRebuild,
                new SegmentIndexRebuildProgress(0, 0, "PREPARING"),
                indexExists, readIndex, readable, false,
                actualDim, actualModel, actualProfileFingerprint);
    }

    SegmentIndexLifecycleState createSucceeded(EmbeddingProfile profile) {
        return new SegmentIndexLifecycleState(
                SegmentIndexStatus.READY, null, null, null,
                true, null, true, true,
                profile.dimension(), profile.modelName(), profile.fingerprint());
    }

    SegmentIndexLifecycleState rebuildSucceeded(EmbeddingProfile profile) {
        return new SegmentIndexLifecycleState(
                SegmentIndexStatus.READY, null, null, rebuildProgress,
                true, null, true, true,
                profile.dimension(), profile.modelName(), profile.fingerprint());
    }

    SegmentIndexLifecycleState rebuildFailed(
            String error,
            boolean aliasReadable,
            boolean aliasWritable
    ) {
        SegmentIndexRebuildProgress failedProgress = rebuildProgress == null
                ? new SegmentIndexRebuildProgress(0, 0, "FAILED")
                : rebuildProgress.withPhase("FAILED");
        return new SegmentIndexLifecycleState(
                SegmentIndexStatus.READY, error, pendingRebuild, failedProgress,
                indexExists, readIndex, aliasReadable, aliasWritable,
                actualDim, actualModel, actualProfileFingerprint);
    }
}

record SegmentIndexPendingRebuild(
        String taskId,
        EmbeddingProfile targetProfile,
        String reason,
        String createdAt
) {
}

record SegmentIndexRebuildProgress(long migrated, long total, String phase) {
    SegmentIndexRebuildProgress withPhase(String newPhase) {
        return new SegmentIndexRebuildProgress(migrated, total, newPhase);
    }
}
