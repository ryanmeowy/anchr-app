package com.anchr.core.kb.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.anchr.core.kb.application.acl.KnowledgeRetrievalCleanupAcl;
import com.anchr.core.kb.application.acl.KnowledgeStorageAcl;
import com.anchr.core.kb.domain.port.KnowledgeObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KnowledgeRetrievalCleanupAcl knowledgeRetrievalCleanupAcl;
    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private KnowledgeObjectStoragePort objectStoragePort;
    @Mock
    private KnowledgeStorageAcl knowledgeStorageAcl;

    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessor(
                outboxEventRepository,
                knowledgeRetrievalCleanupAcl,
                new ObjectMapper(),
                ingestionTaskRepository,
                objectStoragePort,
                knowledgeStorageAcl);
        ReflectionTestUtils.setField(processor, "maxAttempts", 10);
        ReflectionTestUtils.setField(processor, "batchSize", 20);
        ReflectionTestUtils.setField(processor, "lockLeaseMinutes", 5L);
        ReflectionTestUtils.setField(processor, "retentionDays", 90L);
        ReflectionTestUtils.setField(processor, "cleanupBatchSize", 1000);
    }

    @Test
    void poll_shouldClaimWithConfiguredBatchAndLeaseThenProcessClaimedEvent() {
        OutboxEvent event = event(0, validPayload());
        when(outboxEventRepository.claimAvailable(
                any(), any(), eq(20), anyString())).thenAnswer(invocation -> {
                    event.setLockToken(invocation.getArgument(3));
                    return List.of(event);
                });
        when(outboxEventRepository.markDone(
                eq(1L), anyString(), any())).thenReturn(true);
        ArgumentCaptor<LocalDateTime> now =
                ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> expiredBefore =
                ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> lockToken =
                ArgumentCaptor.forClass(String.class);

        processor.poll();

        verify(outboxEventRepository).claimAvailable(
                now.capture(), expiredBefore.capture(), eq(20), lockToken.capture());
        assertThat(expiredBefore.getValue().plusMinutes(5))
                .isEqualTo(now.getValue());
        assertThat(UUID.fromString(lockToken.getValue())).isNotNull();
        verify(outboxEventRepository).markDone(
                eq(1L), eq(lockToken.getValue()), any());
    }

    @Test
    void process_shouldDeleteIndexAndMarkDone() {
        OutboxEvent event = event(0, validPayload());
        when(outboxEventRepository.markDone(eq(1L), eq("claim-1"), any())).thenReturn(true);

        processor.process(event);

        verify(knowledgeRetrievalCleanupAcl).deleteAsset("kb-1", "asset-1");
        verify(outboxEventRepository).markDone(eq(1L), eq("claim-1"), any());
        verify(outboxEventRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void process_shouldDeleteOnlyRequestedGenerationAndMarkDone() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(4L));
        when(outboxEventRepository.markDone(eq(1L), eq("claim-1"), any())).thenReturn(true);

        processor.process(event);

        verify(knowledgeRetrievalCleanupAcl).deleteGeneration("kb-1", "asset-1", 4L);
        verify(knowledgeRetrievalCleanupAcl, never()).deleteAsset(any(), any());
        verify(outboxEventRepository).markDone(eq(1L), eq("claim-1"), any());
        verify(outboxEventRepository, never())
                .markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void process_shouldScheduleFirstRetryAfterOneMinute() {
        OutboxEvent event = event(0, validPayload());
        doThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE))
                .when(knowledgeRetrievalCleanupAcl).deleteAsset("kb-1", "asset-1");
        when(outboxEventRepository.markRetry(eq(1L), eq("claim-1"), eq(1), any(), any(), any()))
                .thenReturn(true);
        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now();

        processor.process(event);

        verify(outboxEventRepository).markRetry(
                eq(1L), eq("claim-1"), eq(1), retryAt.capture(), any(), any());
        assertThat(retryAt.getValue()).isBetween(before.plusSeconds(59), before.plusSeconds(62));
    }

    @Test
    void process_shouldRetryGenerationDeleteAfterTransientFailure() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(4L));
        doThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE))
                .when(knowledgeRetrievalCleanupAcl)
                .deleteGeneration("kb-1", "asset-1", 4L);
        when(outboxEventRepository.markRetry(
                eq(1L), eq("claim-1"), eq(1), any(), any(), any()))
                .thenReturn(true);
        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now();

        processor.process(event);

        verify(outboxEventRepository).markRetry(
                eq(1L), eq("claim-1"), eq(1), retryAt.capture(), any(), any());
        assertThat(retryAt.getValue()).isBetween(
                before.plusSeconds(59), before.plusSeconds(62));
        verify(outboxEventRepository, never()).markDone(anyLong(), anyString(), any());
    }

    @Test
    void process_shouldMoveTenthFailureToFailed() {
        OutboxEvent event = event(9, validPayload());
        doThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE))
                .when(knowledgeRetrievalCleanupAcl).deleteAsset("kb-1", "asset-1");
        when(outboxEventRepository.markFailed(eq(1L), eq("claim-1"), eq(10), any(), any()))
                .thenReturn(true);

        processor.process(event);

        verify(outboxEventRepository).markFailed(eq(1L), eq("claim-1"), eq(10), any(), any());
        verify(outboxEventRepository, never()).markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void process_shouldPermanentlyFailMalformedPayloadWithoutCallingIndex() {
        OutboxEvent event = event(0, "{not-json");
        when(outboxEventRepository.markFailed(eq(1L), eq("claim-1"), eq(0), any(), any()))
                .thenReturn(true);

        processor.process(event);

        verify(outboxEventRepository).markFailed(eq(1L), eq("claim-1"), eq(0), any(), any());
        verify(knowledgeRetrievalCleanupAcl, never()).deleteAsset(any(), any());
        verify(knowledgeRetrievalCleanupAcl, never())
                .deleteGeneration(any(), any(), anyLong());
    }

    @Test
    void process_shouldPermanentlyFailInvalidGenerationPayload() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(-1L));
        when(outboxEventRepository.markFailed(
                eq(1L), eq("claim-1"), eq(0), any(), any()))
                .thenReturn(true);

        processor.process(event);

        verify(outboxEventRepository).markFailed(
                eq(1L), eq("claim-1"), eq(0), any(), any());
        verify(knowledgeRetrievalCleanupAcl, never()).deleteAsset(any(), any());
        verify(knowledgeRetrievalCleanupAcl, never())
                .deleteGeneration(any(), any(), anyLong());
        verify(outboxEventRepository, never()).markDone(anyLong(), anyString(), any());
    }

    @Test
    void process_shouldPermanentlyFailUnknownEventType() {
        OutboxEvent event = event(0, validPayload());
        event.setEventType(OutboxEventType.UNKNOWN);
        when(outboxEventRepository.markFailed(eq(1L), eq("claim-1"), eq(0), any(), any()))
                .thenReturn(true);

        processor.process(event);

        verify(outboxEventRepository).markFailed(eq(1L), eq("claim-1"), eq(0), any(), any());
        verify(knowledgeRetrievalCleanupAcl, never()).deleteAsset(any(), any());
        verify(knowledgeRetrievalCleanupAcl, never())
                .deleteGeneration(any(), any(), anyLong());
    }

    @Test
    void process_shouldDeleteGenerationImagesThroughExistingGenerationEvent() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(4L));
        when(knowledgeStorageAcl.findConfiguredPrefix())
                .thenReturn(Optional.of("embedded"));
        when(outboxEventRepository.markDone(eq(1L), eq("claim-1"), any()))
                .thenReturn(true);

        processor.process(event);

        verify(objectStoragePort).deleteObjectsByPrefix(
                "embedded/ingestion/assets/asset-1/generations/4/images/");
        verify(knowledgeRetrievalCleanupAcl)
                .deleteGeneration("kb-1", "asset-1", 4L);
        verify(outboxEventRepository).markDone(eq(1L), eq("claim-1"), any());
    }

    @Test
    void process_shouldRetryTheExistingEventBeforeDeletingSegmentsWhenImageDeleteFails() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(4L));
        when(knowledgeStorageAcl.findConfiguredPrefix())
                .thenReturn(Optional.of("embedded"));
        doThrow(new IllegalStateException("OSS unavailable"))
                .when(objectStoragePort).deleteObjectsByPrefix(
                        "embedded/ingestion/assets/asset-1/generations/4/images/");
        when(outboxEventRepository.markRetry(
                eq(1L), eq("claim-1"), eq(1), any(), any(), any()))
                .thenReturn(true);

        processor.process(event);

        verify(knowledgeRetrievalCleanupAcl, never())
                .deleteGeneration(anyString(), anyString(), anyLong());
        verify(outboxEventRepository).markRetry(
                eq(1L), eq("claim-1"), eq(1), any(), eq("OSS unavailable"), any());
        verify(outboxEventRepository, never()).markDone(anyLong(), anyString(), any());
    }

    @Test
    void process_shouldRepeatIdempotentImageCleanupWhenRetrievalRetrySucceeds() {
        OutboxEvent event = event(
                OutboxEventType.DELETE_ASSET_GENERATION,
                0,
                generationPayload(4L));
        when(knowledgeStorageAcl.findConfiguredPrefix())
                .thenReturn(Optional.of("embedded"));
        doThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE))
                .doNothing()
                .when(knowledgeRetrievalCleanupAcl)
                .deleteGeneration("kb-1", "asset-1", 4L);
        when(outboxEventRepository.markRetry(
                eq(1L), eq("claim-1"), eq(1), any(), any(), any()))
                .thenReturn(true);

        processor.process(event);
        event.setRetryCount(1);
        event.setLockToken("claim-2");
        when(outboxEventRepository.markDone(
                eq(1L), eq("claim-2"), any())).thenReturn(true);
        processor.process(event);

        verify(objectStoragePort, org.mockito.Mockito.times(2))
                .deleteObjectsByPrefix(
                        "embedded/ingestion/assets/asset-1/generations/4/images/");
        verify(knowledgeRetrievalCleanupAcl, org.mockito.Mockito.times(2))
                .deleteGeneration("kb-1", "asset-1", 4L);
        verify(outboxEventRepository).markDone(
                eq(1L), eq("claim-2"), any());
    }

    @Test
    void process_assetDeleteShouldCleanEveryKnownGenerationBeforeRetrieval() {
        OutboxEvent event = event(0, validPayload());
        when(ingestionTaskRepository.listTargetIndexGenerations("asset-1"))
                .thenReturn(List.of(2L, 4L));
        when(knowledgeStorageAcl.findConfiguredPrefix())
                .thenReturn(Optional.of("embedded"));
        when(outboxEventRepository.markDone(
                eq(1L), eq("claim-1"), any())).thenReturn(true);

        processor.process(event);

        var ordered = org.mockito.Mockito.inOrder(
                objectStoragePort, knowledgeRetrievalCleanupAcl);
        ordered.verify(objectStoragePort).deleteObjectsByPrefix(
                "embedded/ingestion/assets/asset-1/generations/2/images/");
        ordered.verify(objectStoragePort).deleteObjectsByPrefix(
                "embedded/ingestion/assets/asset-1/generations/4/images/");
        ordered.verify(knowledgeRetrievalCleanupAcl)
                .deleteAsset("kb-1", "asset-1");
    }

    @Test
    void cleanupDoneEvents_shouldUseConfiguredRetentionAndBatch() {
        ArgumentCaptor<LocalDateTime> processedBefore =
                ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now().minusDays(90);

        processor.cleanupDoneEvents();

        verify(outboxEventRepository)
                .deleteDoneBefore(processedBefore.capture(), eq(1000));
        assertThat(processedBefore.getValue())
                .isBetween(before.minusSeconds(1), before.plusSeconds(2));
    }

    @Test
    void cleanupDoneEvents_shouldKeepConfiguredCronAndBeijingZone()
            throws Exception {
        Method method = OutboxEventProcessor.class
                .getMethod("cleanupDoneEvents");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${app.outbox.cleanup-cron:0 0 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    private OutboxEvent event(int retryCount, String payload) {
        return event(OutboxEventType.DELETE_ASSET, retryCount, payload);
    }

    private OutboxEvent event(
            OutboxEventType eventType, int retryCount, String payload) {
        return OutboxEvent.builder()
                .id(1L)
                .eventType(eventType)
                .aggregateType("ASSET")
                .aggregateId("asset-1")
                .payload(payload)
                .status(OutboxEventStatus.PROCESSING)
                .retryCount(retryCount)
                .lockToken("claim-1")
                .build();
    }

    private String validPayload() {
        return "{\"kbId\":\"kb-1\",\"assetId\":\"asset-1\"}";
    }

    private String generationPayload(long indexGeneration) {
        return "{\"kbId\":\"kb-1\",\"assetId\":\"asset-1\",\"indexGeneration\":"
                + indexGeneration + "}";
    }
}
