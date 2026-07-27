package com.anchr.core.kb.application.support;

import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AssetCleanupOutboxRecorderTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private AssetCleanupOutboxRecorder recorder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        recorder = new AssetCleanupOutboxRecorder(outboxEventRepository, objectMapper);
    }

    @Test
    void generationRetired_shouldQueuePreviousGenerationDelete() throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 24, 14, 30);

        recorder.generationRetired(
                "kb-1", "asset-1", 3L, "user-a", occurredAt);

        ArgumentCaptor<OutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());

        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType())
                .isEqualTo(OutboxEventType.DELETE_ASSET_GENERATION);
        assertThat(event.getAggregateType()).isEqualTo("ASSET");
        assertThat(event.getAggregateId()).isEqualTo("asset-1");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getCreatedAt()).isEqualTo(occurredAt);
        assertThat(event.getUpdatedAt()).isEqualTo(occurredAt);
        assertThat(objectMapper.readTree(event.getPayload()))
                .isEqualTo(objectMapper.readTree("""
                        {
                          "kbId": "kb-1",
                          "assetId": "asset-1",
                          "indexGeneration": 3
                        }
                        """));
    }

    @Test
    void assetDeleted_shouldQueueFullAssetDelete() throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 24, 15, 0);

        recorder.assetDeleted(
                "kb-1", "asset-1", "user-a", occurredAt);

        ArgumentCaptor<OutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());

        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.DELETE_ASSET);
        assertThat(event.getAggregateType()).isEqualTo("ASSET");
        assertThat(event.getAggregateId()).isEqualTo("asset-1");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(objectMapper.readTree(event.getPayload()))
                .isEqualTo(objectMapper.readTree("""
                        {
                          "kbId": "kb-1",
                          "assetId": "asset-1"
                        }
                        """));
    }

    @Test
    void recorder_shouldPropagateOutboxPersistenceFailure() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxEventRepository).save(any());

        assertThatThrownBy(() -> recorder.assetDeleted(
                "kb-1",
                "asset-1",
                "user-a",
                LocalDateTime.of(2026, 7, 24, 15, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
    }
}
