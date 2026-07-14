package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.application.support.AssetPreviewAccessCache;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetPreviewServiceImplTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private SearchObjectStoragePort objectStoragePort;

    private AssetPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN", "token-hash-a"));
        service = new AssetPreviewServiceImpl(
                knowledgeBaseService,
                objectStoragePort,
                new AssetPreviewAccessCache());
        when(knowledgeBaseService.get("kb-1"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").name("Library A").build());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void getPreview_shouldPreferPreviewObjectKeyAndReturnActualExpiry() {
        Asset asset = assetBuilder()
                .previewObjectKey("preview/document.pdf")
                .objectKey("original/document.pdf")
                .thumbnailKey("thumbnail/document.png")
                .build();
        long previewExpiry = System.currentTimeMillis() + 120_000L;
        long thumbnailExpiry = previewExpiry - 10_000L;
        when(knowledgeBaseService.getDocument("kb-1", "asset-1")).thenReturn(asset);
        when(objectStoragePort.buildPreviewUrl("preview/document.pdf"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl("https://preview", previewExpiry));
        when(objectStoragePort.buildPreviewUrl("thumbnail/document.png"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl("https://thumbnail", thumbnailExpiry));

        var result = service.getPreview("kb-1", "asset-1");

        assertThat(result.getPreviewUrl()).isEqualTo("https://preview");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://thumbnail");
        assertThat(result.getExpiresAt()).isEqualTo(thumbnailExpiry);
        assertThat(result.getVersionNo()).isEqualTo(3);
        verify(objectStoragePort, never()).buildPreviewUrl("original/document.pdf");
    }

    @Test
    void getPreview_shouldFallBackToObjectKeyAndKeepPendingStatusVisible() {
        Asset asset = assetBuilder()
                .parseStatus(DocumentParseStatus.PENDING)
                .indexStatus(DocumentIndexStatus.PENDING)
                .objectKey("original/document.pdf")
                .build();
        long expiresAt = System.currentTimeMillis() + 120_000L;
        when(knowledgeBaseService.getDocument("kb-1", "asset-1")).thenReturn(asset);
        when(objectStoragePort.buildPreviewUrl("original/document.pdf"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl("https://original", expiresAt));

        var result = service.getPreview("kb-1", "asset-1");

        assertThat(result.getPreviewUrl()).isEqualTo("https://original");
        assertThat(result.getParseStatus()).isEqualTo("PENDING");
        assertThat(result.getIndexStatus()).isEqualTo("PENDING");
        assertThat(result.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void getPreview_shouldUseDirectHttpSourceWithoutSigning() {
        Asset asset = assetBuilder()
                .sourceUrl("https://docs.example.com/document.md")
                .build();
        when(knowledgeBaseService.getDocument("kb-1", "asset-1")).thenReturn(asset);

        var result = service.getPreview("kb-1", "asset-1");

        assertThat(result.getPreviewUrl()).isEqualTo("https://docs.example.com/document.md");
        assertThat(result.getExpiresAt()).isNull();
        verifyNoInteractions(objectStoragePort);
    }

    @Test
    void getPreview_shouldRejectAssetWithoutPreviewSource() {
        when(knowledgeBaseService.getDocument("kb-1", "asset-1"))
                .thenReturn(assetBuilder().build());

        assertThatThrownBy(() -> service.getPreview("kb-1", "asset-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getError())
                                .isEqualTo(ApiError.DOCUMENT_PREVIEW_NOT_AVAILABLE));
    }

    private Asset.AssetBuilder assetBuilder() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .title("Document")
                .fileType("PDF")
                .mimeType("application/pdf")
                .sizeBytes(1024L)
                .versionNo(3)
                .parseStatus(DocumentParseStatus.SUCCESS)
                .indexStatus(DocumentIndexStatus.SUCCESS)
                .segmentCount(12)
                .createdAt(LocalDateTime.of(2026, 7, 10, 12, 0));
    }
}
