package com.anchr.core.kb.application.support;

import com.anchr.core.kb.domain.model.DocumentIndexDeletePayload;
import com.anchr.core.kb.domain.model.DocumentIndexGenerationDeletePayload;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Queues durable cleanup of Elasticsearch data owned by a retired generation or Asset.
 *
 * <p>Callers own the surrounding transaction, so event persistence failures roll back the
 * corresponding Asset state change.</p>
 */
@Component
@RequiredArgsConstructor
public class AssetCleanupOutboxRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void generationRetired(String kbId,
                                  String assetId,
                                  long indexGeneration,
                                  String createdBy,
                                  LocalDateTime occurredAt) {
        saveOutbox(
                OutboxEventType.DELETE_ASSET_GENERATION,
                assetId,
                toJson(new DocumentIndexGenerationDeletePayload(
                        kbId, assetId, indexGeneration)),
                createdBy,
                occurredAt);
    }

    public void assetDeleted(String kbId,
                             String assetId,
                             String createdBy,
                             LocalDateTime occurredAt) {
        saveOutbox(
                OutboxEventType.DELETE_ASSET,
                assetId,
                toJson(new DocumentIndexDeletePayload(kbId, assetId)),
                createdBy,
                occurredAt);
    }

    private void saveOutbox(OutboxEventType eventType,
                            String assetId,
                            String payload,
                            String createdBy,
                            LocalDateTime occurredAt) {
        outboxEventRepository.save(OutboxEvent.builder()
                .eventType(eventType)
                .aggregateType("ASSET")
                .aggregateId(assetId)
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .retryCount(0)
                .createdBy(createdBy)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build());
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize asset cleanup event payload.", exception);
        }
    }
}
