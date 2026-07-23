package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Serializes the final index write with document deletion through the asset row lock.
 */
@Service
@RequiredArgsConstructor
public class IngestionIndexFinalizer {

    private static final int INDEX_PROGRESS = 75;
    private static final int DONE_PROGRESS = 100;

    private final AssetRepository assetRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final SegmentBulkWriter segmentBulkWriter;

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeIndex(IngestionTaskItem item,
                                 Asset sourceAsset,
                                 List<Segment> segments,
                                 int segmentCount) {
        LocalDateTime now = LocalDateTime.now();
        if (!ingestionTaskRepository.isClaimCurrentForUpdate(
                item.getId(),
                item.getExecutionEpoch(),
                IngestionExecutionStage.INDEX,
                item.getStageAttempt(),
                item.getLeaseToken())) {
            return false;
        }

        Asset lockedAsset = assetRepository.findByIdForUpdate(
                item.getKbId(), sourceAsset.getId()).orElse(null);
        if (lockedAsset == null || lockedAsset.getDeletedAt() != null) {
            IngestionClaimTransition failed = IngestionClaimTransitions.copyOf(item, now)
                    .nextExecutionStage(IngestionExecutionStage.FAILED)
                    .nextStageAttempt(0)
                    .nextStageRetryCount(item.getStageRetryCount())
                    .nextStageStartedAt(now)
                    .nextActionAt(null)
                    .stage(IngestionStage.INDEX)
                    .status(IngestionTaskItemStatus.FAILED)
                    .progress(Math.max(INDEX_PROGRESS, item.getProgress()))
                    .errorCode(ApiError.DOCUMENT_NOT_FOUND.name())
                    .errorMessage("Document was deleted before indexing completed.")
                    .finishedAt(now)
                    .build();
            if (!ingestionTaskRepository.transitionClaim(failed)) {
                throw new IllegalStateException(
                        "Index claim changed while its item row was locked.");
            }
            return false;
        }

        segmentBulkWriter.write(segments);
        String updatedBy = StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "system";
        boolean assetUpdated = assetRepository.updateIngestionResult(
                item.getKbId(), sourceAsset.getId(),
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(),
                segmentCount, segmentCount, null, null, updatedBy, now);
        if (!assetUpdated) {
            throw new IllegalStateException("Asset disappeared while holding its index finalization lock.");
        }
        IngestionClaimTransition completed = IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(IngestionExecutionStage.COMPLETE)
                .nextStageAttempt(0)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(null)
                .stage(IngestionStage.ASKABLE)
                .status(IngestionTaskItemStatus.SUCCESS)
                .progress(DONE_PROGRESS)
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
}
