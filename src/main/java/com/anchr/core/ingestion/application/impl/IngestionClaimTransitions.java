package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

final class IngestionClaimTransitions {

    private IngestionClaimTransitions() {
    }

    static IngestionClaimTransition.IngestionClaimTransitionBuilder copyOf(
            IngestionTaskItem item, LocalDateTime now) {
        return IngestionClaimTransition.builder()
                .itemId(item.getId())
                .taskId(item.getTaskId())
                .kbId(item.getKbId())
                .executionEpoch(item.getExecutionEpoch())
                .expectedExecutionStage(item.getExecutionStage())
                .expectedStageAttempt(item.getStageAttempt())
                .leaseToken(item.getLeaseToken())
                .nextExecutionStage(item.getExecutionStage())
                .nextStageAttempt(item.getStageAttempt())
                .nextStageRetryCount(item.getStageRetryCount())
                .nextStageStartedAt(item.getStageStartedAt())
                .nextActionAt(item.getNextActionAt())
                .stage(item.getStage())
                .status(item.getStatus())
                .progress(item.getProgress())
                .parseAttempt(item.getParseAttempt())
                .doclingRequestId(item.getDoclingRequestId())
                .doclingJobId(item.getDoclingJobId())
                .sourceRevision(item.getSourceRevision())
                .parseRequestSnapshot(item.getParseRequestSnapshot())
                .parseResultObjectKey(item.getParseResultObjectKey())
                .embeddingResultObjectKey(item.getEmbeddingResultObjectKey())
                .errorCode(item.getErrorCode())
                .errorMessage(item.getErrorMessage())
                .finishedAt(item.getFinishedAt())
                .updatedBy(StringUtils.hasText(item.getTaskCreatedBy())
                        ? item.getTaskCreatedBy() : "system")
                .updatedAt(now);
    }
}
