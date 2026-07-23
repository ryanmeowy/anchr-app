package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private SegmentBulkWriter segmentBulkWriter;

    private IngestionIndexFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new IngestionIndexFinalizer(
                assetRepository, ingestionTaskRepository, segmentBulkWriter);
    }

    @Test
    void finalizeIndex_shouldStopBeforeAssetOrEsWhenClaimIsStale() {
        IngestionTaskItem item = claimedIndexItem();
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(false);

        boolean indexed = finalizer.finalizeIndex(
                item, asset(null), List.of(), 0);

        assertThat(indexed).isFalse();
        verify(assetRepository, never()).findByIdForUpdate(any(), any());
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
                .thenReturn(Optional.of(asset(LocalDateTime.now())));
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(
                item, asset(null), List.of(), 3);

        assertThat(indexed).isFalse();
        verify(segmentBulkWriter, never()).write(any());
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
    void finalizeIndex_shouldWriteAndCompleteUnderItemAndAssetLocks() {
        IngestionTaskItem item = claimedIndexItem();
        Asset source = asset(null);
        List<Segment> segments = List.of(
                Segment.builder().segmentId("segment-1").build());
        when(ingestionTaskRepository.isClaimCurrentForUpdate(
                "item-1", 3L, IngestionExecutionStage.INDEX, 4, "lease-1"))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(source));
        when(assetRepository.updateIngestionResult(
                eq("kb-1"), eq("asset-1"), any(), any(), eq(1), eq(1),
                any(), any(), eq("user-a"), any())).thenReturn(true);
        when(ingestionTaskRepository.transitionClaim(any())).thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(item, source, segments, 1);

        assertThat(indexed).isTrue();
        verify(segmentBulkWriter).write(segments);
        ArgumentCaptor<IngestionClaimTransition> transition =
                ArgumentCaptor.forClass(IngestionClaimTransition.class);
        verify(ingestionTaskRepository).transitionClaim(transition.capture());
        assertThat(transition.getValue().getNextExecutionStage())
                .isEqualTo(IngestionExecutionStage.COMPLETE);
        assertThat(transition.getValue().getStage()).isEqualTo(IngestionStage.ASKABLE);
        assertThat(transition.getValue().getStatus())
                .isEqualTo(IngestionTaskItemStatus.SUCCESS);
        assertThat(transition.getValue().getProgress()).isEqualTo(100);
    }

    private IngestionTaskItem claimedIndexItem() {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .taskCreatedBy("user-a")
                .assetId("asset-1")
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
                .embeddingResultObjectKey("embed.gz")
                .build();
    }

    private Asset asset(LocalDateTime deletedAt) {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .segmentCount(0)
                .indexedSegmentCount(0)
                .deletedAt(deletedAt)
                .build();
    }
}
