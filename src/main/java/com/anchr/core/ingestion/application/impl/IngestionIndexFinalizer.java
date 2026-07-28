package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
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

/**
 * Serializes the final index write with document deletion through the asset row lock.
 */
@Service
@RequiredArgsConstructor
public class IngestionIndexFinalizer {

    private final AssetRepository assetRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final SegmentRepository segmentRepository;
    private final SegmentBulkWriter segmentBulkWriter;
    private final AssetCleanupOutboxRecorder assetCleanupOutboxRecorder;
    private final IngestionArtifactCleanupRecorder artifactCleanupRecorder;

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeIndex(IngestionTaskItem item,
                                 Asset sourceAsset,
                                 List<Segment> segments) {
        LocalDateTime now = LocalDateTime.now();
        if (!ingestionTaskRepository.isClaimCurrentForUpdate(
                item.getId(),
                item.getExecutionEpoch(),
                IngestionExecutionStage.INDEX,
                item.getClaimVersion(),
                item.getLeaseToken())) {
            return false;
        }

        Asset lockedAsset = assetRepository.findByIdForUpdate(
                item.getKbId(), sourceAsset.getId()).orElse(null);
        if (lockedAsset == null || lockedAsset.getDeletedAt() != null) {
            return failClaim(
                    item,
                    ApiError.DOCUMENT_NOT_FOUND,
                    "Document was deleted before indexing completed.",
                    now,
                    null);
        }

        long targetGeneration = requireTargetGeneration(item);
        long previousGeneration = lockedAsset.getActiveIndexGeneration();
        if (targetGeneration <= previousGeneration) {
            return failClaim(
                    item,
                    ApiError.INTERNAL_ERROR,
                    "Index generation was superseded before activation.",
                    now,
                    targetGeneration < previousGeneration
                            ? targetGeneration : null);
        }

        validateSegments(segments, lockedAsset, targetGeneration);
        segmentRepository.deleteByAssetGeneration(
                lockedAsset.getId(), targetGeneration);
        segmentBulkWriter.write(segments);
        String updatedBy = StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "system";
        int readableSegmentCount = countReadableSegments(segments);
        boolean assetUpdated = assetRepository.activateIndexGeneration(
                item.getKbId(), lockedAsset.getId(),
                previousGeneration, targetGeneration,
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(),
                readableSegmentCount, readableSegmentCount, updatedBy, now);
        if (!assetUpdated) {
            throw new IllegalStateException(
                    "Asset generation changed while holding its index finalization lock.");
        }
        assetCleanupOutboxRecorder.generationRetired(
                item.getKbId(),
                lockedAsset.getId(),
                previousGeneration,
                updatedBy,
                now);
        deleteOverwrittenAsset(item, lockedAsset.getId(), updatedBy, now);

        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.success();
        IngestionClaimTransition completed = IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(IngestionExecutionStage.COMPLETE)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(null)
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .errorCode(null)
                .errorMessage(null)
                .finishedAt(now)
                .build();
        boolean itemUpdated = ingestionTaskRepository.transitionClaim(completed);
        if (!itemUpdated) {
            throw new IllegalStateException(
                    "Index claim changed while its item row was locked.");
        }
        return true;
    }

    private boolean failClaim(IngestionTaskItem item,
                              ApiError error,
                              String message,
                              LocalDateTime now,
                              Long inactiveGeneration) {
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.failed(
                        IngestionExecutionStage.INDEX, item.getProgress());
        IngestionClaimTransition failed = IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(IngestionExecutionStage.FAILED)
                .nextStageRetryCount(item.getStageRetryCount())
                .nextStageStartedAt(now)
                .nextActionAt(null)
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .errorCode(error.name())
                .errorMessage(message)
                .finishedAt(now)
                .build();
        if (!ingestionTaskRepository.transitionClaim(failed)) {
            throw new IllegalStateException(
                    "Index claim changed while its item row was locked.");
        }
        artifactCleanupRecorder.terminalFailure(failed);
        if (inactiveGeneration != null) {
            assetCleanupOutboxRecorder.generationRetired(
                    item.getKbId(),
                    item.getAssetId(),
                    inactiveGeneration,
                    item.getTaskCreatedBy(),
                    now);
        }
        return false;
    }

    private long requireTargetGeneration(IngestionTaskItem item) {
        Long targetGeneration = item.getTargetIndexGeneration();
        if (targetGeneration == null || targetGeneration < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return targetGeneration;
    }

    private void validateSegments(List<Segment> segments,
                                  Asset asset,
                                  long targetGeneration) {
        if (segments == null) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Segments must not be null.");
        }
        if (segments.isEmpty()
                && !"image".equalsIgnoreCase(asset.getFileType())) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "No segments are available for indexing a non-image document.");
        }
        for (Segment segment : segments) {
            if (segment == null
                    || !asset.getId().equals(segment.getAssetId())
                    || segment.getIndexGeneration() != targetGeneration) {
                throw new BusinessException(
                        ApiError.INTERNAL_ERROR,
                        "Segment generation does not match the ingestion target.");
            }
        }
    }

    static int countReadableSegments(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        return (int) segments.stream()
                .filter(Objects::nonNull)
                .filter(segment -> segment.getSegmentType()
                        != SegmentType.IMAGE_VISUAL)
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
        if (oldAsset == null || oldAsset.getDeletedAt() != null) {
            return;
        }
        if (!assetRepository.markDeleted(
                item.getKbId(), oldAssetId, updatedBy, now)) {
            throw new IllegalStateException(
                    "Overwritten asset changed while holding its row lock.");
        }
        assetCleanupOutboxRecorder.assetDeleted(
                item.getKbId(),
                oldAssetId,
                updatedBy,
                now);
    }
}
