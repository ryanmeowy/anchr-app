package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Serializes final index activation with Asset deletion. */
@Service
@RequiredArgsConstructor
public class IngestionIndexFinalizer {

    private final AssetRepository assetRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final SegmentRepository segmentRepository;
    private final SegmentBulkWriter segmentBulkWriter;
    private final AssetCleanupOutboxRecorder assetCleanupOutboxRecorder;

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeIndex(IngestionTaskItem item,
                                 Asset sourceAsset,
                                 List<Segment> segments) {
        LocalDateTime now = LocalDateTime.now();
        if (!ingestionTaskRepository.isRunningForUpdate(
                item.getId(), IngestionStage.INDEX)) {
            return false;
        }

        Asset lockedAsset = assetRepository.findByIdForUpdate(
                item.getKbId(), sourceAsset.getId()).orElse(null);
        if (lockedAsset == null || lockedAsset.getDeletedAt() != null) {
            throw new BusinessException(
                    ApiError.DOCUMENT_NOT_FOUND,
                    "Document was deleted before indexing completed.");
        }

        long targetGeneration = requireTargetGeneration(item);
        long previousGeneration = lockedAsset.getActiveIndexGeneration();
        if (targetGeneration <= previousGeneration) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Index generation was superseded before activation.");
        }

        validateSegments(segments, lockedAsset, targetGeneration);
        segmentRepository.deleteByAssetGeneration(lockedAsset.getId(), targetGeneration);
        segmentBulkWriter.write(segments);

        String updatedBy = updatedBy(item);
        int readableSegmentCount = countReadableSegments(segments);
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

    private long requireTargetGeneration(IngestionTaskItem item) {
        Long generation = item.getTargetIndexGeneration();
        if (generation == null || generation < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return generation;
    }

    private void validateSegments(List<Segment> segments, Asset asset, long generation) {
        if (segments == null) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Segments must not be null.");
        }
        if (segments.isEmpty() && !"image".equalsIgnoreCase(asset.getFileType())) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "No segments are available for indexing a non-image document.");
        }
        for (Segment segment : segments) {
            if (segment == null || !asset.getId().equals(segment.getAssetId())
                    || segment.getIndexGeneration() != generation) {
                throw new BusinessException(
                        ApiError.INTERNAL_ERROR,
                        "Segment generation does not match the ingestion target.");
            }
        }
    }

    static int countReadableSegments(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return 0;
        return (int) segments.stream()
                .filter(Objects::nonNull)
                .filter(segment -> segment.getSegmentType() != SegmentType.IMAGE_VISUAL)
                .count();
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
}
