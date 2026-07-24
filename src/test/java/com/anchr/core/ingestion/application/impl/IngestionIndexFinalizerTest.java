package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.application.support.AssetIndexChangeRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.repository.SegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionIndexFinalizerTest {

    @Mock
    private AssetRepository assetRepository;
    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private SegmentBulkWriter segmentBulkWriter;
    @Mock
    private AssetIndexChangeRecorder assetIndexChangeRecorder;

    private IngestionIndexFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new IngestionIndexFinalizer(
                assetRepository,
                ingestionTaskRepository,
                segmentRepository,
                segmentBulkWriter,
                assetIndexChangeRecorder);
    }

    @Test
    void finalizeIndex_shouldStopBeforeAssetOrEsWhenClaimIsStale() {
        IngestionTaskItem item = claimedIndexItem();
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(false);

        boolean indexed = finalizer.finalizeIndex(
                item, asset("asset-1", 0L, null), List.of());

        assertThat(indexed).isFalse();
        verify(assetRepository, never()).findByIdForUpdate(any(), any());
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
        verify(segmentBulkWriter, never()).write(any());
        verify(ingestionTaskRepository, never()).transitionClaim(any());
    }

    @Test
    void finalizeIndex_shouldFenceFailureWhenDeleteCommittedFirst() {
        IngestionTaskItem item = claimedIndexItem();
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(
                        asset("asset-1", 0L, LocalDateTime.now())));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(
                item, asset("asset-1", 0L, null), List.of());

        assertThat(indexed).isFalse();
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
        verify(segmentBulkWriter, never()).write(any());
        verify(assetRepository, never()).activateIndexGeneration(
                any(), any(), anyLong(), anyLong(),
                any(), any(), anyInt(), anyInt(), any(), any());
        verify(assetIndexChangeRecorder, never()).generationActivated(
                any(), any(), anyLong(), anyLong(), any(), any());
        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(ingestionTaskRepository).transitionClaim(transition.capture());
        assertThat(transition.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.FAILED);
        assertThat(transition.getValue().getStatus())
                .isEqualTo(IngestionTaskItemStatus.FAILED);
        assertThat(transition.getValue().getErrorCode())
                .isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void finalizeIndex_shouldRejectSupersededGenerationBeforeWritingEs() {
        IngestionTaskItem item = claimedIndexItem();
        Asset source = asset("asset-1", 1L, null);
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(source));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(item, source, List.of());

        assertThat(indexed).isFalse();
        verify(segmentRepository, never()).deleteByAssetGeneration(any(), anyLong());
        verify(segmentBulkWriter, never()).write(any());
        verify(assetRepository, never()).activateIndexGeneration(
                any(), any(), anyLong(), anyLong(),
                any(), any(), anyInt(), anyInt(), any(), any());
        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(ingestionTaskRepository).transitionClaim(transition.capture());
        assertThat(transition.getValue().getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(transition.getValue().getErrorMessage())
                .contains("superseded");
    }

    @Test
    void finalizeIndex_shouldWriteAndCompleteUnderItemAndAssetLocks() {
        IngestionTaskItem item = claimedIndexItem();
        Asset source = asset("asset-1", 0L, null);
        List<Segment> segments = List.of(segment("segment-1", "asset-1", 1L));
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(source));
        when(assetRepository.activateIndexGeneration(
                eq("kb-1"), eq("asset-1"), eq(0L), eq(1L),
                any(), any(), eq(1), eq(1), eq("user-a"), any()))
                .thenReturn(true);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(item, source, segments);

        assertThat(indexed).isTrue();
        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        InOrder order = inOrder(
                ingestionTaskRepository,
                assetRepository,
                segmentRepository,
                segmentBulkWriter,
                assetIndexChangeRecorder);
        order.verify(ingestionTaskRepository).isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1");
        order.verify(assetRepository).findByIdForUpdate("kb-1", "asset-1");
        order.verify(segmentRepository).deleteByAssetGeneration("asset-1", 1L);
        order.verify(segmentBulkWriter).write(segments);
        order.verify(assetRepository).activateIndexGeneration(
                eq("kb-1"), eq("asset-1"), eq(0L), eq(1L),
                any(), any(), eq(1), eq(1), eq("user-a"), any());
        order.verify(assetIndexChangeRecorder).generationActivated(
                eq("kb-1"), eq("asset-1"), eq(1L), eq(0L),
                eq("user-a"), any());
        order.verify(ingestionTaskRepository).transitionClaim(transition.capture());
        assertThat(transition.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.COMPLETE);
        assertThat(transition.getValue().getStage()).isEqualTo(IngestionStage.ASKABLE);
        assertThat(transition.getValue().getStatus())
                .isEqualTo(IngestionTaskItemStatus.SUCCESS);
        assertThat(transition.getValue().getProgress()).isEqualTo(100);
    }

    @Test
    void finalizeIndex_shouldRecordOverwrittenAssetDeletionInSameFlow() {
        IngestionTaskItem item = claimedIndexItem().toBuilder()
                .dedupeResult(DedupeResult.OVERWRITTEN)
                .duplicateAssetId("asset-old")
                .build();
        Asset source = asset("asset-1", 0L, null);
        Asset oldAsset = asset("asset-old", 5L, null);
        List<Segment> segments = List.of(segment("segment-1", "asset-1", 1L));
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(source));
        when(assetRepository.findByIdForUpdate("kb-1", "asset-old"))
                .thenReturn(Optional.of(oldAsset));
        when(assetRepository.activateIndexGeneration(
                eq("kb-1"), eq("asset-1"), eq(0L), eq(1L),
                any(), any(), eq(1), eq(1), eq("user-a"), any()))
                .thenReturn(true);
        when(assetRepository.markDeleted(
                eq("kb-1"), eq("asset-old"), eq("user-a"), any()))
                .thenReturn(true);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(item, source, segments);

        assertThat(indexed).isTrue();
        verify(assetIndexChangeRecorder).generationActivated(
                eq("kb-1"), eq("asset-1"), eq(1L), eq(0L),
                eq("user-a"), any());
        verify(assetRepository).markDeleted(
                eq("kb-1"), eq("asset-old"), eq("user-a"), any());
        verify(assetIndexChangeRecorder).assetDeleted(
                eq("kb-1"), eq("asset-old"), eq(5L), eq("user-a"), any());
        verify(segmentRepository, never()).deleteByAssetId("asset-old");
    }

    private IngestionTaskItem claimedIndexItem() {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .taskCreatedBy("user-a")
                .assetId("asset-1")
                .targetIndexGeneration(1L)
                .executionStage(IngestionExecutionStage.INDEX)
                .executionEpoch(3L)
                .claimVersion(4)
                .stageRetryCount(1)
                .stageStartedAt(LocalDateTime.now().minusMinutes(1))
                .leaseToken("lease-1")
                .leaseUntil(LocalDateTime.now().plusMinutes(5))
                .stage(IngestionStage.INDEX)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(75)
                .parseAttempt(2)
                .doclingRequestId("task-1:item-1:2")
                .sourceRevision("v1:revision")
                .parseResultObjectKey("parse.gz")
                .build();
    }

    private Asset asset(String assetId,
                        long activeIndexGeneration,
                        LocalDateTime deletedAt) {
        return Asset.builder()
                .id(assetId)
                .kbId("kb-1")
                .activeIndexGeneration(activeIndexGeneration)
                .segmentCount(0)
                .indexedSegmentCount(0)
                .deletedAt(deletedAt)
                .build();
    }

    private Segment segment(String segmentId,
                            String assetId,
                            long indexGeneration) {
        return Segment.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .indexGeneration(indexGeneration)
                .build();
    }
}
