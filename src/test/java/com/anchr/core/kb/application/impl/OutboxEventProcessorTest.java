package com.anchr.core.kb.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

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
    private SegmentRepository segmentRepository;

    private OutboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessor(outboxEventRepository, segmentRepository, new ObjectMapper());
        ReflectionTestUtils.setField(processor, "maxAttempts", 10);
    }

    @Test
    void process_shouldDeleteIndexAndMarkDone() {
        OutboxEvent event = event(0, validPayload());
        when(outboxEventRepository.markDone(eq(1L), eq("claim-1"), any())).thenReturn(true);

        processor.process(event);

        verify(segmentRepository).deleteByAssetId("asset-1");
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

        verify(segmentRepository).deleteByAssetGeneration("asset-1", 4L);
        verify(segmentRepository, never()).deleteByAssetId(any());
        verify(outboxEventRepository).markDone(eq(1L), eq("claim-1"), any());
        verify(outboxEventRepository, never())
                .markRetry(anyLong(), anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void process_shouldScheduleFirstRetryAfterOneMinute() {
        OutboxEvent event = event(0, validPayload());
        doThrow(new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE))
                .when(segmentRepository).deleteByAssetId("asset-1");
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
                .when(segmentRepository).deleteByAssetGeneration("asset-1", 4L);
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
                .when(segmentRepository).deleteByAssetId("asset-1");
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
        verify(segmentRepository, never()).deleteByAssetId(any());
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
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
        verify(segmentRepository, never()).deleteByAssetId(any());
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
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
        verify(segmentRepository, never()).deleteByAssetId(any());
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
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
