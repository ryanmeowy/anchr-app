package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Activates a remotely written generation in one short MySQL transaction. */
@Service
@RequiredArgsConstructor
public class IngestionIndexFinalizer {

    private final AssetRepository assetRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetCleanupOutboxRecorder assetCleanupOutboxRecorder;

    @Transactional(rollbackFor = Exception.class)
    public boolean activateGeneration(IngestionTaskItem item,
                                      Asset sourceAsset,
                                      int readableSegmentCount,
                                      RetrievalGenerationWriteReceipt writeReceipt) {
        LocalDateTime now = LocalDateTime.now();
        long targetGeneration = requireTargetGeneration(item);
        validateReceipt(item, sourceAsset, readableSegmentCount, writeReceipt);
        if (!ingestionTaskRepository.isRunningForUpdate(
                item.getId(), IngestionStage.INDEX)) {
            retireIfInactive(item, sourceAsset.getId(), targetGeneration, now);
            return false;
        }

        Asset lockedAsset = assetRepository.findByIdForUpdate(
                item.getKbId(), sourceAsset.getId()).orElse(null);
        if (lockedAsset == null || lockedAsset.getDeletedAt() != null) {
            throw new BusinessException(
                    ApiError.DOCUMENT_NOT_FOUND,
                    "Document was deleted before indexing completed.");
        }

        long previousGeneration = lockedAsset.getActiveIndexGeneration();
        if (targetGeneration <= previousGeneration) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Index generation was superseded before activation.");
        }

        String updatedBy = updatedBy(item);
        if (!assetRepository.activateIndexGeneration(
                item.getKbId(), lockedAsset.getId(), previousGeneration, targetGeneration,
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(),
                readableSegmentCount, readableSegmentCount, updatedBy, now)) {
            throw new IllegalStateException(
                    "Asset generation changed while holding its finalization lock.");
        }
        assetCleanupOutboxRecorder.generationRetired(
                item.getKbId(), lockedAsset.getId(), previousGeneration, updatedBy, now);
        deleteOverwrittenAsset(item, lockedAsset.getId(), updatedBy, now);

        if (!ingestionTaskRepository.completeRunningItem(
                item.getKbId(), item.getTaskId(), item.getId(),
                IngestionStage.INDEX, updatedBy, now)) {
            throw new IllegalStateException(
                    "Ingestion item changed while its index was being finalized.");
        }
        return true;
    }

    private void validateReceipt(IngestionTaskItem item,
                                 Asset asset,
                                 int readableSegmentCount,
                                 RetrievalGenerationWriteReceipt receipt) {
        if (receipt == null
                || !item.getKbId().equals(receipt.kbId())
                || !asset.getId().equals(receipt.assetId())
                || requireTargetGeneration(item) != receipt.generation()
                || readableSegmentCount < 0
                || receipt.writtenCount() < readableSegmentCount) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Retrieval generation write receipt does not match the ingestion target.");
        }
    }

    private void retireIfInactive(IngestionTaskItem item,
                                  String assetId,
                                  long generation,
                                  LocalDateTime now) {
        Asset current = assetRepository.findByIdForUpdate(item.getKbId(), assetId).orElse(null);
        if (current != null
                && current.getDeletedAt() == null
                && current.getActiveIndexGeneration() == generation) {
            return;
        }
        assetCleanupOutboxRecorder.generationRetired(
                item.getKbId(), assetId, generation, updatedBy(item), now);
    }

    private long requireTargetGeneration(IngestionTaskItem item) {
        Long generation = item.getTargetIndexGeneration();
        if (generation == null || generation < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return generation;
    }

    private void deleteOverwrittenAsset(IngestionTaskItem item,
                                        String newAssetId,
                                        String updatedBy,
                                        LocalDateTime now) {
        if (item.getDedupeResult() != DedupeResult.OVERWRITTEN
                || !StringUtils.hasText(item.getDuplicateAssetId())
                || newAssetId.equals(item.getDuplicateAssetId().trim())) {
            return;
        }
        String oldAssetId = item.getDuplicateAssetId().trim();
        Asset oldAsset = assetRepository.findByIdForUpdate(
                item.getKbId(), oldAssetId).orElse(null);
        if (oldAsset == null || oldAsset.getDeletedAt() != null) return;
        if (!assetRepository.markDeleted(item.getKbId(), oldAssetId, updatedBy, now)) {
            throw new IllegalStateException(
                    "Overwritten asset changed while holding its row lock.");
        }
        assetCleanupOutboxRecorder.assetDeleted(
                item.getKbId(), oldAssetId, updatedBy, now);
    }

    private String updatedBy(IngestionTaskItem item) {
        return StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "ingestion-worker";
    }

    static int countReadableSegments(List<IngestionIndexSegment> segments) {
        if (segments == null || segments.isEmpty()) return 0;
        return (int) segments.stream()
                .filter(Objects::nonNull)
                .filter(segment -> !"IMAGE_VISUAL".equals(segment.segmentType()))
                .count();
    }
}
