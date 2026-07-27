package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.artifact.IngestionArtifactPaths;
import com.anchr.core.ingestion.domain.model.IngestionAttemptArtifactDeletePayload;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/** Records cleanup work in the existing outbox when an ingestion attempt fails. */
@Component
@RequiredArgsConstructor
public class IngestionArtifactCleanupRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void terminalFailure(IngestionClaimTransition transition) {
        String imagePrefix = imagePrefix(transition);
        String parsePrefix = IngestionArtifactPaths.parseAttemptPrefix(
                transition.getTaskId(), transition.getItemId(), transition.getParseAttempt());
        IngestionAttemptArtifactDeletePayload payload =
                new IngestionAttemptArtifactDeletePayload(
                        transition.getTaskId(),
                        transition.getItemId(),
                        transition.getExecutionEpoch(),
                        transition.getParseAttempt(),
                        imagePrefix,
                        parsePrefix);
        LocalDateTime now = transition.getUpdatedAt() == null
                ? LocalDateTime.now() : transition.getUpdatedAt();
        outboxEventRepository.save(OutboxEvent.builder()
                .eventType(OutboxEventType.DELETE_INGESTION_ATTEMPT_ARTIFACTS)
                .aggregateType("INGESTION_ITEM")
                .aggregateId(transition.getItemId())
                .payload(toJson(payload))
                .status(OutboxEventStatus.PENDING)
                .retryCount(0)
                .createdBy(StringUtils.hasText(transition.getUpdatedBy())
                        ? transition.getUpdatedBy() : "ingestion-scheduler")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private String imagePrefix(IngestionClaimTransition transition) {
        if (!StringUtils.hasText(transition.getParseRequestSnapshot())) return null;
        try {
            IngestionParseRequestSnapshot snapshot = objectMapper.readValue(
                    transition.getParseRequestSnapshot(), IngestionParseRequestSnapshot.class);
            if (snapshot.ossTarget() == null
                    || !IngestionArtifactPaths.ATTEMPT_PREFIX_LAYOUT.equals(
                            snapshot.ossTarget().objectKeyLayout())) {
                return null;
            }
            String prefix = snapshot.ossTarget().basePath();
            return IngestionArtifactPaths.isExpectedImagePrefix(
                    prefix,
                    transition.getTaskId(),
                    transition.getItemId(),
                    transition.getParseAttempt())
                    ? prefix : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String toJson(IngestionAttemptArtifactDeletePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize ingestion artifact cleanup event.", exception);
        }
    }
}
