package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.application.acl.KnowledgeActivityAcl;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private KnowledgeActivityAcl activityEventRepository;
    @Mock
    private AssetCleanupOutboxRecorder assetCleanupOutboxRecorder;
    @Mock
    private IdGen idGen;

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        service = new KnowledgeBaseServiceImpl(
                knowledgeBaseRepository,
                assetRepository,
                activityEventRepository,
                assetCleanupOutboxRecorder,
                idGen);
        when(knowledgeBaseRepository.findActiveById("kb-1"))
                .thenReturn(Optional.of(KnowledgeBase.builder()
                        .id("kb-1")
                        .status(KnowledgeBaseStatus.ACTIVE)
                        .build()));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void deleteDocument_shouldLockSoftDeleteAndQueueAssetCleanup() {
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset("asset-1", 7L)));
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(true);

        service.deleteDocument("kb-1", "asset-1");

        var ordered = inOrder(assetRepository, assetCleanupOutboxRecorder);
        ordered.verify(assetRepository).findByIdForUpdate("kb-1", "asset-1");
        ordered.verify(assetRepository).markDeleted(
                eq("kb-1"), eq("asset-1"), eq("user-a"), any());
        ordered.verify(assetCleanupOutboxRecorder).assetDeleted(
                eq("kb-1"), eq("asset-1"), eq("user-a"), any());
        verify(knowledgeBaseRepository).refreshDocumentStats("kb-1", "user-a", false);
        verify(activityEventRepository).deleteCitationOpenedByAssetId("user-a", "asset-1");
    }

    @Test
    void deleteDocument_shouldStopBeforeMutationWhenDocumentDoesNotExist() {
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(BusinessException.class);

        verify(assetRepository, never()).markDeleted(any(), any(), any(), any());
        verify(assetCleanupOutboxRecorder, never())
                .assetDeleted(any(), any(), any(), any());
        verify(activityEventRepository, never()).deleteCitationOpenedByAssetId(any(), any());
        verify(knowledgeBaseRepository, never()).refreshDocumentStats(any(), any(), eq(false));
    }

    @Test
    void deleteDocument_shouldNotQueueCleanupWhenLockedDeleteLosesRace() {
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset("asset-1", 5L)));
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(BusinessException.class);

        verify(assetCleanupOutboxRecorder, never())
                .assetDeleted(any(), any(), any(), any());
        verify(activityEventRepository, never()).deleteCitationOpenedByAssetId(any(), any());
        verify(knowledgeBaseRepository, never()).refreshDocumentStats(any(), any(), eq(false));
    }

    @Test
    void deleteDocument_shouldPropagateOutboxFailureForTransactionRollback() {
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset("asset-1", 5L)));
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(assetCleanupOutboxRecorder)
                .assetDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any());

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void deleteDocument_shouldPropagateSynchronousActivityCleanupFailureForTransactionRollback() {
        when(assetRepository.findByIdForUpdate("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset("asset-1", 5L)));
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(true);
        doThrow(new IllegalStateException("activity cleanup unavailable"))
                .when(activityEventRepository).deleteCitationOpenedByAssetId("user-a", "asset-1");

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("activity cleanup unavailable");

        verify(assetCleanupOutboxRecorder, never()).assetDeleted(any(), any(), any(), any());
    }

    @Test
    void listDocuments_shouldNormalizeFiltersAndSupportLibraryPageSizes() {
        when(assetRepository.listActive(
                "kb-1", "RAG", "PDF",
                DocumentAvailabilityStatus.ANSWERABLE, 24, 24))
                .thenReturn(List.of());
        when(assetRepository.countActive(
                "kb-1", "RAG", "PDF", DocumentAvailabilityStatus.ANSWERABLE))
                .thenReturn(186L);
        when(assetRepository.sumActiveSegments(
                "kb-1", "RAG", "PDF", DocumentAvailabilityStatus.ANSWERABLE))
                .thenReturn(4832L);

        var result = service.listDocuments(
                "kb-1", " RAG ", "pdf",
                DocumentAvailabilityStatus.ANSWERABLE, 2, 24);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(24);
        assertThat(result.total()).isEqualTo(186L);
        assertThat(result.segmentTotal()).isEqualTo(4832L);
        verify(assetRepository).listActive(
                "kb-1", "RAG", "PDF",
                DocumentAvailabilityStatus.ANSWERABLE, 24, 24);
    }

    @Test
    void listDocuments_shouldUseFiftyAsDefaultSizeAndClampInvalidBounds() {
        when(assetRepository.listActive("kb-1", null, null, null, 50, 0))
                .thenReturn(List.of());
        when(assetRepository.listActive("kb-1", null, null, null, 1, 0))
                .thenReturn(List.of());
        when(assetRepository.listActive("kb-1", null, null, null, 100, 0))
                .thenReturn(List.of());
        when(assetRepository.countActive("kb-1", null, null, null)).thenReturn(0L);
        when(assetRepository.sumActiveSegments("kb-1", null, null, null)).thenReturn(0L);

        var defaultResult = service.listDocuments("kb-1", null, null, null, null);
        var minimumResult = service.listDocuments("kb-1", null, null, 0, 0);
        var maximumResult = service.listDocuments("kb-1", null, null, -3, 1000);

        assertThat(defaultResult.size()).isEqualTo(50);
        assertThat(minimumResult.page()).isEqualTo(1);
        assertThat(minimumResult.size()).isEqualTo(1);
        assertThat(maximumResult.page()).isEqualTo(1);
        assertThat(maximumResult.size()).isEqualTo(100);
    }

    private Asset asset(String id, long activeIndexGeneration) {
        return Asset.builder()
                .id(id)
                .kbId("kb-1")
                .activeIndexGeneration(activeIndexGeneration)
                .build();
    }
}
