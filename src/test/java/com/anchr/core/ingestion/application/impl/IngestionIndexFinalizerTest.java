package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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
    @Mock private AssetCleanupOutboxRecorder cleanupRecorder;

    private IngestionIndexFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new IngestionIndexFinalizer(
                assetRepository, repository, cleanupRecorder);
    }

    @Test
    void activateGeneration_shouldSwitchGenerationAndCompleteItem() {
        IngestionTaskItem item = item();
        Asset asset = asset();
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

        assertThat(finalizer.activateGeneration(item, asset, 1, receipt())).isTrue();

        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(1L), eq("user-1"),
                any(LocalDateTime.class));
    }

    @Test
    void activateGeneration_whenItemIsNotRunning_shouldRetireUnactivatedGeneration() {
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(false);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset()));

        assertThat(finalizer.activateGeneration(item(), asset(), 1, receipt())).isFalse();

        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(2L), eq("user-1"),
                any(LocalDateTime.class));
        verify(assetRepository, never()).activateIndexGeneration(
                any(), any(), any(Long.class), any(Long.class), any(), any(),
                any(Integer.class), any(Integer.class), any(), any());
    }

    @Test
    void activateGeneration_whenTargetAlreadyActive_shouldNotRetireIt() {
        Asset activeTarget = asset().toBuilder().activeIndexGeneration(2L).build();
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(false);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(activeTarget));

        assertThat(finalizer.activateGeneration(item(), asset(), 1, receipt())).isFalse();

        verify(cleanupRecorder, never()).generationRetired(
                any(), any(), any(Long.class), any(), any());
    }

    @Test
    void activateGeneration_whenAssetWasDeleted_shouldFailShortTransaction() {
        when(repository.isRunningForUpdate("item-1", IngestionStage.INDEX)).thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> finalizer.activateGeneration(
                item(), asset(), 1, receipt()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activateGeneration_shouldRejectReceiptForAnotherGeneration() {
        RetrievalGenerationWriteReceipt mismatched =
                new RetrievalGenerationWriteReceipt(
                        "kb-1", "asset-1", 3L, 1, "index", "profile");

        assertThatThrownBy(() -> finalizer.activateGeneration(
                item(), asset(), 1, mismatched))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("receipt");

        verify(repository, never()).isRunningForUpdate(any(), any());
    }

    private RetrievalGenerationWriteReceipt receipt() {
        return new RetrievalGenerationWriteReceipt(
                "kb-1", "asset-1", 2L, 1, "kb_segment_write", "profile");
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
}
