package com.anchr.core.kb.application.support;

import com.anchr.core.kb.domain.model.AssetIndexChange;
import com.anchr.core.kb.domain.model.AssetIndexChangeOperation;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.AssetIndexChangeRepository;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AssetIndexChangeRecorderTest {

    @Mock
    private AssetIndexChangeRepository assetIndexChangeRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private AssetIndexChangeRecorder recorder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        recorder = new AssetIndexChangeRecorder(
                assetIndexChangeRepository,
                outboxEventRepository,
                objectMapper);
    }

    @Test
    void generationActivated_shouldAppendChangeThenQueuePreviousGenerationDelete()
            throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 24, 14, 30);

        recorder.generationActivated(
                "kb-1", "asset-1", 4L, 3L, "user-a", occurredAt);

        ArgumentCaptor<AssetIndexChange> changeCaptor =
                ArgumentCaptor.forClass(AssetIndexChange.class);
        ArgumentCaptor<OutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        InOrder ordered = inOrder(assetIndexChangeRepository, outboxEventRepository);
        ordered.verify(assetIndexChangeRepository).save(changeCaptor.capture());
        ordered.verify(outboxEventRepository).save(outboxCaptor.capture());

        AssetIndexChange change = changeCaptor.getValue();
        assertThat(change.getRevision()).isNull();
        assertThat(change.getEventId()).matches("[0-9a-f]{32}");
        assertThat(change.getKbId()).isEqualTo("kb-1");
        assertThat(change.getAssetId()).isEqualTo("asset-1");
        assertThat(change.getOperation())
                .isEqualTo(AssetIndexChangeOperation.GENERATION_ACTIVATED);
        assertThat(change.getIndexGeneration()).isEqualTo(4L);
        assertThat(change.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(change.getCreatedBy()).isEqualTo("user-a");

        OutboxEvent outbox = outboxCaptor.getAllValues().getFirst();
        assertThat(outbox.getEventType())
                .isEqualTo(OutboxEventType.DELETE_ASSET_GENERATION);
        assertThat(outbox.getAggregateType()).isEqualTo("ASSET");
        assertThat(outbox.getAggregateId()).isEqualTo("asset-1");
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outbox.getRetryCount()).isZero();
        assertThat(outbox.getCreatedAt()).isEqualTo(occurredAt);
        assertThat(outbox.getUpdatedAt()).isEqualTo(occurredAt);
        assertThat(objectMapper.readTree(outbox.getPayload()))
                .isEqualTo(objectMapper.readTree("""
                        {
                          "kbId": "kb-1",
                          "assetId": "asset-1",
                          "indexGeneration": 3
                        }
                        """));
    }

    @Test
    void generationActivated_shouldNotQueueCleanupForSameGeneration() {
        recorder.generationActivated(
                "kb-1",
                "asset-1",
                4L,
                4L,
                "user-a",
                LocalDateTime.of(2026, 7, 24, 14, 30));

        ArgumentCaptor<AssetIndexChange> changeCaptor =
                ArgumentCaptor.forClass(AssetIndexChange.class);
        verify(assetIndexChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getOperation())
                .isEqualTo(AssetIndexChangeOperation.GENERATION_ACTIVATED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void assetDeleted_shouldAppendDeleteChangeAndQueueFullAssetDelete()
            throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 24, 15, 0);

        recorder.assetDeleted(
                "kb-1", "asset-1", 7L, "user-a", occurredAt);

        ArgumentCaptor<AssetIndexChange> changeCaptor =
                ArgumentCaptor.forClass(AssetIndexChange.class);
        ArgumentCaptor<OutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        verify(assetIndexChangeRepository).save(changeCaptor.capture());
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(changeCaptor.getValue().getOperation())
                .isEqualTo(AssetIndexChangeOperation.ASSET_DELETED);
        assertThat(changeCaptor.getValue().getIndexGeneration()).isEqualTo(7L);
        assertThat(outboxCaptor.getAllValues().getFirst().getEventType())
                .isEqualTo(OutboxEventType.DELETE_ASSET);
        assertThat(objectMapper.readTree(outboxCaptor.getAllValues().getFirst().getPayload()))
                .isEqualTo(objectMapper.readTree("""
                        {
                          "kbId": "kb-1",
                          "assetId": "asset-1"
                        }
                        """));
    }

    @Test
    void recorder_shouldPropagateChangeFailureWithoutQueueingCleanup() {
        doThrow(new IllegalStateException("change unavailable"))
                .when(assetIndexChangeRepository).save(any());

        assertThatThrownBy(() -> recorder.assetDeleted(
                "kb-1",
                "asset-1",
                2L,
                "user-a",
                LocalDateTime.of(2026, 7, 24, 15, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("change unavailable");

        verify(outboxEventRepository, never()).save(any());
    }
}
