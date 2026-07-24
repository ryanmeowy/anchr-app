package com.anchr.core.kb.application.support;

import com.anchr.core.kb.domain.model.AssetIndexChange;
import com.anchr.core.kb.domain.model.AssetIndexChangeOperation;
import com.anchr.core.kb.domain.model.DocumentIndexDeletePayload;
import com.anchr.core.kb.domain.model.DocumentIndexGenerationDeletePayload;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.AssetIndexChangeRepository;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Appends the replayable business change and its ES-cleanup outbox event.
 *
 * <p>Callers own the surrounding transaction. A serialization or persistence
 * failure must roll back the corresponding Asset state change.</p>
 */
@Component
@RequiredArgsConstructor
public class AssetIndexChangeRecorder {

    private final AssetIndexChangeRepository assetIndexChangeRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void generationActivated(String kbId,
                                    String assetId,
                                    long activeGeneration,
                                    long previousGeneration,
                                    String createdBy,
                                    LocalDateTime occurredAt) {
        saveChange(
                kbId,
                assetId,
                AssetIndexChangeOperation.GENERATION_ACTIVATED,
                activeGeneration,
                createdBy,
                occurredAt);
        if (previousGeneration != activeGeneration) {
            saveOutbox(
                    OutboxEventType.DELETE_ASSET_GENERATION,
                    assetId,
                    toJson(new DocumentIndexGenerationDeletePayload(
                            kbId, assetId, previousGeneration)),
                    createdBy,
                    occurredAt);
        }
    }

    public void assetDeleted(String kbId,
                             String assetId,
                             long activeGeneration,
                             String createdBy,
                             LocalDateTime occurredAt) {
        saveChange(
                kbId,
                assetId,
                AssetIndexChangeOperation.ASSET_DELETED,
                activeGeneration,
                createdBy,
                occurredAt);
        saveOutbox(
                OutboxEventType.DELETE_ASSET,
                assetId,
                toJson(new DocumentIndexDeletePayload(kbId, assetId)),
                createdBy,
                occurredAt);
    }

    private void saveChange(String kbId,
                            String assetId,
                            AssetIndexChangeOperation operation,
                            long indexGeneration,
                            String createdBy,
                            LocalDateTime occurredAt) {
        assetIndexChangeRepository.save(AssetIndexChange.builder()
                .eventId(UUID.randomUUID().toString().replace("-", ""))
                .kbId(kbId)
                .assetId(assetId)
                .operation(operation)
                .indexGeneration(indexGeneration)
                .occurredAt(occurredAt)
                .createdBy(createdBy)
                .build());
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
                    "Failed to serialize asset index event payload.", exception);
        }
    }
}
