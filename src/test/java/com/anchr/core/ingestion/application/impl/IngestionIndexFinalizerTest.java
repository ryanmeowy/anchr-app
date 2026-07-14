package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void finalizeIndex_shouldSkipWriteWhenDeleteCommittedFirst() {
        Asset source = asset(null);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset(LocalDateTime.now())));

        boolean indexed = finalizer.finalizeIndex(
                "kb-1", "task-1", "item-1", source, List.of(), 3, "user-a");

        assertThat(indexed).isFalse();
        verify(segmentBulkWriter, never()).write(any());
        verify(ingestionTaskRepository).markItemFailed(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("INDEX"), eq(75),
                eq("DOCUMENT_NOT_FOUND"), any(), any());
    }

    @Test
    void finalizeIndex_shouldWriteAndCompleteWhileHoldingActiveAssetLock() {
        Asset source = asset(null);
        List<Segment> segments = List.of(Segment.builder().segmentId("segment-1").build());
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(source));
        when(assetRepository.updateIngestionResult(
                eq("kb-1"), eq("asset-1"), any(), any(), eq(1), eq(1),
                any(), any(), eq("user-a"), any())).thenReturn(true);
        when(ingestionTaskRepository.markItemSuccess(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("ASKABLE"), eq(100), any()))
                .thenReturn(true);

        boolean indexed = finalizer.finalizeIndex(
                "kb-1", "task-1", "item-1", source, segments, 1, "user-a");

        assertThat(indexed).isTrue();
        verify(segmentBulkWriter).write(segments);
        verify(ingestionTaskRepository).markItemSuccess(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq("ASKABLE"), eq(100), any());
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
