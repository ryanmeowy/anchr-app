package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionStageTransactionCoordinatorTest {

    @Mock private IngestionTaskRepository repository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetCleanupOutboxRecorder cleanupRecorder;

    private IngestionStageTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new IngestionStageTransactionCoordinator(
                repository, assetRepository, cleanupRecorder);
    }

    @Test
    void ensureTargetIndexGeneration_shouldAllocateUnderAssetLock() {
        IngestionTaskItem item = item().toBuilder().targetIndexGeneration(null).build();
        Asset asset = asset(3L);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
        when(repository.findMaxTargetIndexGeneration("asset-1")).thenReturn(5L);
        when(repository.assignTargetIndexGeneration(
                eq("item-1"), eq("asset-1"), eq(6L), any(LocalDateTime.class)))
                .thenReturn(true);

        IngestionTaskItem allocated = coordinator.ensureTargetIndexGeneration(item);

        assertThat(allocated.getTargetIndexGeneration()).isEqualTo(6L);
    }

    @Test
    void advance_shouldUpdateItemAndAssetInSameBoundary() {
        IngestionTaskItem item = item();
        Asset asset = asset(0L);
        when(repository.advanceRunningItem(
                eq("kb-1"), eq("task-1"), eq("item-1"),
                eq(IngestionStage.PARSE), eq(IngestionStage.EMBED), eq(55),
                any(LocalDateTime.class))).thenReturn(true);
        when(assetRepository.updateStatuses(
                eq("kb-1"), eq("asset-1"), eq("SUCCESS"), eq("PENDING"),
                eq("user-1"), any(LocalDateTime.class))).thenReturn(true);

        assertThat(coordinator.advanceAndUpdateAssetStatus(
                item, IngestionStage.EMBED, 55, asset, "SUCCESS", "PENDING"))
                .isTrue();
    }

    @Test
    void fail_shouldRetireInactiveGenerationWithoutImageSpecificRegistry() {
        IngestionTaskItem item = item().toBuilder().targetIndexGeneration(2L).build();
        Asset asset = asset(1L);
        when(repository.failRunningItem(
                eq("kb-1"), eq("task-1"), eq("item-1"), eq(IngestionStage.PARSE),
                eq(20), eq("TEXT_PARSE_FAILED"), eq("failed"), eq("user-1"),
                any(LocalDateTime.class))).thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));

        assertThat(coordinator.failRunning(
                item, asset, ApiError.TEXT_PARSE_FAILED, "failed", "FAILED", "FAILED"))
                .isTrue();

        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(2L), eq("user-1"),
                any(LocalDateTime.class));
    }

    @Test
    void fail_whenItemAlreadyChanged_shouldStillRetireUnactivatedGeneration() {
        IngestionTaskItem item = item().toBuilder().targetIndexGeneration(2L).build();
        when(repository.failRunningItem(any(), any(), any(), any(),
                any(Integer.class), any(), any(), any(), any())).thenReturn(false);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset(1L)));

        assertThat(coordinator.failRunning(
                item, asset(1L), ApiError.INTERNAL_ERROR, "failed", "FAILED", "FAILED"))
                .isFalse();

        verify(assetRepository, never()).updateIngestionResult(
                any(), any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(), any(), any());
        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(2L), eq("user-1"),
                any(LocalDateTime.class));
    }

    @Test
    void fail_whenTargetGenerationIsAlreadyActive_shouldNeverRetireIt() {
        IngestionTaskItem item = item().toBuilder().targetIndexGeneration(2L).build();
        when(repository.failRunningItem(any(), any(), any(), any(),
                any(Integer.class), any(), any(), any(), any())).thenReturn(false);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset(2L)));

        assertThat(coordinator.failRunning(
                item, asset(1L), ApiError.INTERNAL_ERROR, "failed", "FAILED", "FAILED"))
                .isFalse();

        verify(cleanupRecorder, never()).generationRetired(
                any(), any(), any(Long.class), any(), any());
    }

    @Test
    void fail_whenAssetWasDeleted_shouldStillRetireRemoteGeneration() {
        IngestionTaskItem item = item().toBuilder().targetIndexGeneration(2L).build();
        Asset deleted = asset(1L).toBuilder().deletedAt(LocalDateTime.now()).build();
        when(repository.failRunningItem(any(), any(), any(), any(),
                any(Integer.class), any(), any(), any(), any())).thenReturn(true);
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(deleted));

        assertThat(coordinator.failRunning(
                item, deleted, ApiError.INTERNAL_ERROR, "failed", "FAILED", "FAILED"))
                .isTrue();

        verify(cleanupRecorder).generationRetired(
                eq("kb-1"), eq("asset-1"), eq(2L), eq("user-1"),
                any(LocalDateTime.class));
    }

    private IngestionTaskItem item() {
        return IngestionTaskItem.builder()
                .id("item-1").taskId("task-1").kbId("kb-1")
                .taskCreatedBy("user-1").assetId("asset-1")
                .targetIndexGeneration(1L)
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(20)
                .build();
    }

    private Asset asset(long activeGeneration) {
        return Asset.builder()
                .id("asset-1").kbId("kb-1")
                .activeIndexGeneration(activeGeneration)
                .build();
    }
}
