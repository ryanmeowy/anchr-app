package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.ingestion.domain.model.IngestionStage;
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
    public boolean finalizeIndex(String kbId, String taskId, String itemId,
                                 Asset sourceAsset, List<Segment> segments,
                                 int segmentCount, String userId) {
        LocalDateTime now = LocalDateTime.now();
        Asset lockedAsset = assetRepository.findByIdForUpdate(kbId, sourceAsset.getId()).orElse(null);
        if (lockedAsset == null || lockedAsset.getDeletedAt() != null) {
            ingestionTaskRepository.markItemFailed(kbId, taskId, itemId,
                    IngestionStage.INDEX.name(), INDEX_PROGRESS,
                    ApiError.DOCUMENT_NOT_FOUND.name(),
                    "Document was deleted before indexing completed.", now);
            ingestionTaskRepository.refreshSummary(kbId, taskId, userId, now);
            return false;
        }

        segmentBulkWriter.write(segments);
        boolean assetUpdated = assetRepository.updateIngestionResult(kbId, sourceAsset.getId(),
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(),
                segmentCount, segmentCount, null, null, userId, now);
        if (!assetUpdated) {
            throw new IllegalStateException("Asset disappeared while holding its index finalization lock.");
        }
        boolean itemUpdated = ingestionTaskRepository.markItemSuccess(kbId, taskId, itemId,
                IngestionStage.ASKABLE.name(), DONE_PROGRESS, now);
        if (!itemUpdated) {
            throw new IllegalStateException("Ingestion item disappeared during index finalization.");
        }
        ingestionTaskRepository.refreshSummary(kbId, taskId, userId, now);
        return true;
    }
}
