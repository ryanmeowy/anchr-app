package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Keeps item state and its Asset projection in one short MySQL transaction. */
@Service
@RequiredArgsConstructor
public class IngestionStageTransactionCoordinator {

    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetRepository assetRepository;
    private final AssetCleanupOutboxRecorder assetCleanupOutboxRecorder;

    @Transactional(rollbackFor = Exception.class)
    public IngestionTaskItem ensureTargetIndexGeneration(IngestionTaskItem item) {
        if (item.getTargetIndexGeneration() != null) return item;
        Asset asset = assetRepository.findByIdForUpdate(item.getKbId(), item.getAssetId())
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
        long targetGeneration = Math.addExact(
                Math.max(asset.getActiveIndexGeneration(),
                        ingestionTaskRepository.findMaxTargetIndexGeneration(asset.getId())),
                1L);
        boolean assigned = ingestionTaskRepository.assignTargetIndexGeneration(
                item.getId(), asset.getId(), targetGeneration, LocalDateTime.now());
        long stored = assigned ? targetGeneration
                : ingestionTaskRepository.findTargetIndexGeneration(item.getId(), asset.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Ingestion target generation disappeared during allocation."));
        return item.toBuilder().targetIndexGeneration(stored).build();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean updateAssetStatus(IngestionTaskItem item,
                                     Asset asset,
                                     String parseStatus,
                                     String indexStatus) {
        if (!ingestionTaskRepository.isRunningForUpdate(item.getId(), item.getStage())) {
            return false;
        }
        if (!assetRepository.updateStatuses(
                item.getKbId(), asset.getId(), parseStatus, indexStatus,
                updatedBy(item), LocalDateTime.now())) {
            throw new IllegalStateException(
                    "Document disappeared while updating its ingestion status.");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean advanceAndUpdateAssetStatus(IngestionTaskItem item,
                                               IngestionStage nextStage,
                                               int progress,
                                               Asset asset,
                                               String parseStatus,
                                               String indexStatus) {
        LocalDateTime now = LocalDateTime.now();
        if (!ingestionTaskRepository.advanceRunningItem(
                item.getKbId(), item.getTaskId(), item.getId(), item.getStage(),
                nextStage, progress, now)) {
            return false;
        }
        if (!assetRepository.updateStatuses(
                item.getKbId(), asset.getId(), parseStatus, indexStatus,
                updatedBy(item), now)) {
            throw new IllegalStateException(
                    "Document disappeared while advancing its ingestion stage.");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean failRunning(IngestionTaskItem item,
                               Asset asset,
                               ApiError error,
                               String message,
                               String parseStatus,
                               String indexStatus) {
        LocalDateTime now = LocalDateTime.now();
        if (!ingestionTaskRepository.failRunningItem(
                item.getKbId(), item.getTaskId(), item.getId(), item.getStage(),
                item.getProgress(), error.name(), message, updatedBy(item), now)) {
            return false;
        }
        if (asset != null) {
            Asset current = assetRepository.findByIdForUpdate(
                    item.getKbId(), asset.getId()).orElse(null);
            if (current != null && current.getDeletedAt() == null) {
                assetRepository.updateIngestionResult(
                        item.getKbId(), current.getId(), parseStatus, indexStatus,
                        current.getSegmentCount(), current.getIndexedSegmentCount(),
                        error.name(), message, updatedBy(item), now);
                retireInactiveGeneration(item, current, now);
            }
        }
        return true;
    }

    private void retireInactiveGeneration(
            IngestionTaskItem item, Asset asset, LocalDateTime now) {
        Long generation = item.getTargetIndexGeneration();
        if (generation == null || generation < 1L
                || generation == asset.getActiveIndexGeneration()) {
            return;
        }
        assetCleanupOutboxRecorder.generationRetired(
                item.getKbId(), asset.getId(), generation, updatedBy(item), now);
    }

    private String updatedBy(IngestionTaskItem item) {
        return item.getTaskCreatedBy() == null || item.getTaskCreatedBy().isBlank()
                ? "ingestion-worker" : item.getTaskCreatedBy();
    }
}
