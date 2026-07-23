package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Keeps fenced item transitions and their MySQL asset projection in one short transaction.
 */
@Service
@RequiredArgsConstructor
public class IngestionStageTransactionCoordinator {

    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetRepository assetRepository;

    @Transactional(rollbackFor = Exception.class)
    public boolean updateAssetStatusForCurrentClaim(IngestionTaskItem item,
                                                    Asset asset,
                                                    String parseStatus,
                                                    String indexStatus) {
        if (!ingestionTaskRepository.isClaimCurrentForUpdate(
                item.getId(),
                item.getExecutionEpoch(),
                item.getExecutionStage(),
                item.getStageAttempt(),
                item.getLeaseToken())) {
            return false;
        }
        boolean updated = assetRepository.updateStatuses(
                item.getKbId(),
                asset.getId(),
                parseStatus,
                indexStatus,
                item.getTaskCreatedBy(),
                LocalDateTime.now());
        if (!updated) {
            throw new IllegalStateException(
                    "Document disappeared while updating its ingestion status.");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean transitionAndUpdateAssetStatus(IngestionClaimTransition transition,
                                                  Asset asset,
                                                  String parseStatus,
                                                  String indexStatus) {
        if (!ingestionTaskRepository.transitionClaim(transition)) {
            return false;
        }
        boolean updated = assetRepository.updateStatuses(
                transition.getKbId(),
                asset.getId(),
                parseStatus,
                indexStatus,
                transition.getUpdatedBy(),
                transitionTime(transition));
        if (!updated) {
            throw new IllegalStateException(
                    "Document disappeared while advancing its ingestion stage.");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean transitionFailed(IngestionClaimTransition transition,
                                    Asset asset,
                                    String parseStatus,
                                    String indexStatus,
                                    int segmentCount,
                                    int indexedSegmentCount) {
        if (!ingestionTaskRepository.transitionClaim(transition)) {
            return false;
        }
        if (asset != null) {
            // A concurrent delete is allowed to win. The item failure is still authoritative,
            // while an inactive asset must not be resurrected merely to record an error.
            assetRepository.updateIngestionResult(
                    transition.getKbId(),
                    asset.getId(),
                    parseStatus,
                    indexStatus,
                    segmentCount,
                    indexedSegmentCount,
                    transition.getErrorCode(),
                    transition.getErrorMessage(),
                    transition.getUpdatedBy(),
                    transitionTime(transition));
        }
        return true;
    }

    private LocalDateTime transitionTime(IngestionClaimTransition transition) {
        return transition.getUpdatedAt() == null ? LocalDateTime.now() : transition.getUpdatedAt();
    }
}
