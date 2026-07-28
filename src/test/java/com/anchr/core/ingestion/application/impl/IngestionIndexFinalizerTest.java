package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionIndexFinalizerTest {

    @Mock private AssetRepository assetRepository;
    @Mock private IngestionTaskRepository repository;
    @Mock private SegmentRepository segmentRepository;
    @Mock private SegmentBulkWriter bulkWriter;
    @Mock private AssetCleanupOutboxRecorder cleanupRecorder;

    private IngestionIndexFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new IngestionIndexFinalizer(
                assetRepository, repository, segmentRepository, bulkWriter, cleanupRecorder);
    }

    @Test
    void finalizeIndex_shouldActivateGenerationAndCompleteItem() {
        IngestionTaskItem item = item();
        Asset asset = asset();
        List<Segment> segments = List.of(segment());
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(assetRepository.activateIndexGeneration(
                eq("kb-1"), eq("asset-1"), eq(1L), eq(2L),
                eq("SUCCESS"), eq("SUCCESS"), eq(1), eq(1), eq("user-1"),
                any(LocalDateTime.class))).thenReturn(true);
        when(repository.completeRunningItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(IngestionStage.INDEX),
                eq("user-1"), any(LocalDateTime.class))).thenReturn(true);

        assertThat(finalizer.finalizeIndex(item, asset, segments)).isTrue();

        verify(segmentRepository).deleteByAssetGeneration("asset-1", 2L);
        verify(bulkWriter).write(segments);
        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(1L), eq("user-1"),
                any(LocalDateTime.class));
    }

    @Test
    void finalizeIndex_whenItemIsNotRunning_shouldStopBeforeExternalWrites() {
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(false);

        assertThat(finalizer.finalizeIndex(item(), asset(), List.of(segment()))).isFalse();

        verify(bulkWriter, never()).write(any());
    }

    @Test
    void finalizeIndex_whenAssetWasDeleted_shouldFailWholeRun() {
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> finalizer.finalizeIndex(item(), asset(), List.of(segment())))
                .isInstanceOf(BusinessException.class);
    }

    private IngestionTaskItem item() {
        return IngestionTaskItem.builder()
                .id("item-1").taskId("task-1").kbId("kb-1")
                .taskCreatedBy("user-1").assetId("asset-1")
                .targetIndexGeneration(2L)
                .stage(IngestionStage.INDEX)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(75)
                .build();
    }

    private Asset asset() {
        return Asset.builder()
                .id("asset-1").kbId("kb-1").fileType("PDF")
                .activeIndexGeneration(1L)
                .build();
    }

    private Segment segment() {
        return Segment.builder()
                .segmentId("segment-1").kbId("kb-1").assetId("asset-1")
                .indexGeneration(2L).segmentType(SegmentType.TEXT_CHUNK)
                .build();
    }
}
