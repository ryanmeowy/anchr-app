package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.support.PreviewAccessCache;
import com.smart.vision.core.search.domain.model.Bbox;
import com.smart.vision.core.search.domain.model.KbAssetTypeEnum;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.model.SegmentType;
import com.smart.vision.core.search.domain.port.SearchObjectStoragePort;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SegmentPreviewServiceImplTest {

    private static final long PREVIEW_URL_TTL_MILLIS = 5 * 60 * 1_000L;

    @Test
    void getSegmentPreview_shouldExposeImageOcrAnchor() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = buildImageOcrSegment();
        when(kbSegmentRepository.findBySegmentId("asset-1:ocr:0")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("image-a.png")).thenReturn("https://preview.example.com/image-a.png");

        PreviewSegmentDTO preview = service.getSegmentPreview("asset-1:ocr:0", "token-a");

        assertThat(preview.getSegmentId()).isEqualTo("asset-1:ocr:0");
        assertThat(preview.getPreviewType()).isEqualTo("IMAGE");
        assertThat(preview.getPreviewUrl()).isEqualTo("https://preview.example.com/image-a.png");
        assertThat(preview.getExpiresAt()).isNotNull();
        assertThat(preview.getFileName()).isEqualTo("image-a.png");
        assertThat(preview.getSnippet()).isEqualTo("设备故障代码 E102");
        assertThat(preview.getAnchor().getBbox().getX()).isEqualTo(120);
        assertThat(preview.getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(preview.getAnchor().getImageWidth()).isEqualTo(1920);
        assertThat(preview.getAnchor().getImageHeight()).isEqualTo(1080);
        assertThat(preview.getSurroundingChunks()).hasSize(1);
        assertThat(preview.getSurroundingChunks().getFirst().getRelation()).isEqualTo("current");
        assertThat(preview.getSurroundingChunks().getFirst().getContent()).isEqualTo("设备故障代码 E102");
    }

    @Test
    void getSegmentPreview_shouldSetExpectedExpiryForSignedUrl() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = textSegment("asset-6:text:1", "oss://docs/expiry.pdf");
        when(kbSegmentRepository.findBySegmentId("asset-6:text:1")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("docs/expiry.pdf")).thenReturn("https://preview.example.com/expiry.pdf");

        long before = System.currentTimeMillis();
        PreviewSegmentDTO preview = service.getSegmentPreview("asset-6:text:1", "token-a");
        long after = System.currentTimeMillis();

        assertThat(preview.getExpiresAt()).isBetween(before + PREVIEW_URL_TTL_MILLIS, after + PREVIEW_URL_TTL_MILLIS);
    }

    @Test
    void getSegmentPreview_shouldReturnNeighborChunksWithContentLimit() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = Segment.builder()
                .segmentId("asset-2:text:3")
                .assetId("asset-2")
                .assetType(KbAssetTypeEnum.TEXT)
                .segmentType(SegmentType.TEXT_CHUNK)
                .title("manual.md")
                .contentText("检索定位内容".repeat(2000))
                .sourceRef("oss://docs/manual.md?signature=mock")
                .pageNo(2)
                .chunkOrder(3)
                .build();
        Segment previous = Segment.builder()
                .segmentId("asset-2:text:2")
                .assetId("asset-2")
                .assetType(KbAssetTypeEnum.TEXT)
                .segmentType(SegmentType.TEXT_CHUNK)
                .contentText("上一段内容")
                .pageNo(2)
                .chunkOrder(2)
                .build();
        Segment next = Segment.builder()
                .segmentId("asset-2:text:4")
                .assetId("asset-2")
                .assetType(KbAssetTypeEnum.TEXT)
                .segmentType(SegmentType.TEXT_CHUNK)
                .contentText("下一段内容")
                .pageNo(2)
                .chunkOrder(4)
                .build();
        when(kbSegmentRepository.findBySegmentId("asset-2:text:3")).thenReturn(Optional.of(segment));
        when(kbSegmentRepository.findNeighborChunks("asset-2", 2, 3, 1))
                .thenReturn(List.of(previous, segment, next));
        when(objectStoragePort.buildPreviewUrl("docs/manual.md")).thenReturn("https://preview.example.com/docs/manual.md");

        PreviewSegmentDTO preview = service.getSegmentPreview("asset-2:text:3", "token-a");

        assertThat(preview.getFileName()).isEqualTo("manual.md");
        assertThat(preview.getPreviewType()).isEqualTo("MD");
        assertThat(preview.getPreviewUrl()).isEqualTo("https://preview.example.com/docs/manual.md");
        assertThat(preview.getAnchor().getChunkOrder()).isEqualTo(3);
        assertThat(preview.getSurroundingChunks()).hasSize(3);
        assertThat(preview.getSurroundingChunks()).extracting("relation")
                .containsExactly("previous", "current", "next");
        assertThat(preview.getSurroundingChunks().get(1).getSegmentId()).isEqualTo("asset-2:text:3");
        assertThat(preview.getSurroundingChunks().get(1).getChunkOrder()).isEqualTo(3);
        assertThat(preview.getSurroundingChunks().get(1).getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void getSegmentPreview_shouldInferPdfAndTxtPreviewTypes() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment pdfSegment = textSegment("asset-3:text:1", "oss://docs/mysql.pdf");
        Segment txtSegment = textSegment("asset-4:text:1", "oss://docs/readme.txt");
        when(kbSegmentRepository.findBySegmentId("asset-3:text:1")).thenReturn(Optional.of(pdfSegment));
        when(kbSegmentRepository.findBySegmentId("asset-4:text:1")).thenReturn(Optional.of(txtSegment));
        when(objectStoragePort.buildPreviewUrl("docs/mysql.pdf")).thenReturn("https://preview.example.com/mysql.pdf");
        when(objectStoragePort.buildPreviewUrl("docs/readme.txt")).thenReturn("https://preview.example.com/readme.txt");

        PreviewSegmentDTO pdfPreview = service.getSegmentPreview("asset-3:text:1", "token-a");
        PreviewSegmentDTO txtPreview = service.getSegmentPreview("asset-4:text:1", "token-a");

        assertThat(pdfPreview.getPreviewType()).isEqualTo("PDF");
        assertThat(txtPreview.getPreviewType()).isEqualTo("TXT");
    }

    @Test
    void getSegmentPreview_shouldInferMarkdownAndImageFallbackPreviewTypes() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment markdownSegment = textSegment("asset-7:text:1", "oss://docs/runbook.markdown");
        Segment imageSegment = Segment.builder()
                .segmentId("asset-8:image:1")
                .assetId("asset-8")
                .assetType(KbAssetTypeEnum.IMAGE)
                .segmentType(SegmentType.IMAGE_CAPTION)
                .title("scan-without-extension")
                .contentText("image caption")
                .sourceRef("oss://images/scan-without-extension")
                .build();
        when(kbSegmentRepository.findBySegmentId("asset-7:text:1")).thenReturn(Optional.of(markdownSegment));
        when(kbSegmentRepository.findBySegmentId("asset-8:image:1")).thenReturn(Optional.of(imageSegment));
        when(objectStoragePort.buildPreviewUrl("docs/runbook.markdown")).thenReturn("https://preview.example.com/runbook.markdown");
        when(objectStoragePort.buildPreviewUrl("images/scan-without-extension")).thenReturn("https://preview.example.com/scan");

        PreviewSegmentDTO markdownPreview = service.getSegmentPreview("asset-7:text:1", "token-a");
        PreviewSegmentDTO imagePreview = service.getSegmentPreview("asset-8:image:1", "token-a");

        assertThat(markdownPreview.getPreviewType()).isEqualTo("MD");
        assertThat(imagePreview.getPreviewType()).isEqualTo("IMAGE");
    }

    @Test
    void getSegmentPreview_shouldReusePreviewUrlForSameTokenHash() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = buildImageOcrSegment();
        when(kbSegmentRepository.findBySegmentId("asset-1:ocr:0")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("image-a.png")).thenReturn("https://preview.example.com/image-a.png");

        PreviewSegmentDTO firstPreview = service.getSegmentPreview("asset-1:ocr:0", "token-a");
        PreviewSegmentDTO secondPreview = service.getSegmentPreview("asset-1:ocr:0", "token-a");

        assertThat(firstPreview.getPreviewUrl()).isEqualTo("https://preview.example.com/image-a.png");
        assertThat(secondPreview.getPreviewUrl()).isEqualTo(firstPreview.getPreviewUrl());
        assertThat(secondPreview.getExpiresAt()).isEqualTo(firstPreview.getExpiresAt());
        verify(objectStoragePort, times(1)).buildPreviewUrl("image-a.png");
    }

    @Test
    void getSegmentPreview_shouldSignAgainWhenPreviewCacheMissesAfterTtl() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache() {
                    @Override
                    public Optional<PreviewAccess> find(String segmentId, String accessToken) {
                        return Optional.empty();
                    }
                }
        );
        Segment segment = textSegment("asset-9:text:1", "oss://docs/cache.pdf");
        when(kbSegmentRepository.findBySegmentId("asset-9:text:1")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("docs/cache.pdf"))
                .thenReturn("https://preview.example.com/cache-1.pdf")
                .thenReturn("https://preview.example.com/cache-2.pdf");

        PreviewSegmentDTO firstPreview = service.getSegmentPreview("asset-9:text:1", "token-a");
        PreviewSegmentDTO secondPreview = service.getSegmentPreview("asset-9:text:1", "token-a");

        assertThat(firstPreview.getPreviewUrl()).isEqualTo("https://preview.example.com/cache-1.pdf");
        assertThat(secondPreview.getPreviewUrl()).isEqualTo("https://preview.example.com/cache-2.pdf");
        verify(objectStoragePort, times(2)).buildPreviewUrl("docs/cache.pdf");
    }

    @Test
    void getSegmentPreview_shouldReturnEmptySurroundingChunksWhenContentMissing() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = Segment.builder()
                .segmentId("asset-10:text:1")
                .assetId("asset-10")
                .assetType(KbAssetTypeEnum.TEXT)
                .segmentType(SegmentType.TEXT_CHUNK)
                .sourceRef("https://preview.example.com/direct.txt")
                .chunkOrder(1)
                .build();
        when(kbSegmentRepository.findBySegmentId("asset-10:text:1")).thenReturn(Optional.of(segment));
        when(kbSegmentRepository.findNeighborChunks("asset-10", null, 1, 1)).thenReturn(List.of());

        PreviewSegmentDTO preview = service.getSegmentPreview("asset-10:text:1", "token-a");

        assertThat(preview.getPreviewUrl()).isEqualTo("https://preview.example.com/direct.txt");
        assertThat(preview.getExpiresAt()).isNull();
        assertThat(preview.getSurroundingChunks()).isEmpty();
    }

    @Test
    void getSegmentPreview_shouldReturnImagePreviewWhenBboxInvalid() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = Segment.builder()
                .segmentId("asset-11:ocr:0")
                .assetId("asset-11")
                .assetType(KbAssetTypeEnum.IMAGE)
                .segmentType(SegmentType.IMAGE_OCR_BLOCK)
                .title("invalid-bbox.png")
                .ocrText("仍然返回 OCR 命中文本")
                .sourceRef("oss://invalid-bbox.png")
                .bbox(Bbox.builder()
                        .x(1200)
                        .y(900)
                        .width(-10)
                        .height(0)
                        .unit("PERCENT")
                        .build())
                .imageWidth(1000)
                .imageHeight(800)
                .build();
        when(kbSegmentRepository.findBySegmentId("asset-11:ocr:0")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("invalid-bbox.png")).thenReturn("https://preview.example.com/invalid-bbox.png");

        PreviewSegmentDTO preview = service.getSegmentPreview("asset-11:ocr:0", "token-a");

        assertThat(preview.getPreviewType()).isEqualTo("IMAGE");
        assertThat(preview.getPreviewUrl()).isEqualTo("https://preview.example.com/invalid-bbox.png");
        assertThat(preview.getSnippet()).isEqualTo("仍然返回 OCR 命中文本");
        assertThat(preview.getAnchor().getBbox().getWidth()).isEqualTo(-10);
        assertThat(preview.getAnchor().getBbox().getUnit()).isEqualTo("PERCENT");
    }

    @Test
    void getSegmentPreview_shouldThrowWhenSegmentNotFound() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        when(kbSegmentRepository.findBySegmentId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSegmentPreview("missing", "token-a"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Segment not found.");
    }

    @Test
    void getSegmentPreview_shouldThrowWhenTokenMissing() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );

        assertThatThrownBy(() -> service.getSegmentPreview("asset-1:ocr:0", " "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("X-Access-Token is required.");
    }

    @Test
    void getSegmentPreview_shouldThrowClearErrorWhenPreviewUrlSigningFails() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                kbSegmentRepository,
                objectStoragePort,
                new PreviewAccessCache()
        );
        Segment segment = textSegment("asset-5:text:1", "oss://docs/fail.pdf");
        when(kbSegmentRepository.findBySegmentId("asset-5:text:1")).thenReturn(Optional.of(segment));
        when(objectStoragePort.buildPreviewUrl("docs/fail.pdf")).thenThrow(new IllegalStateException("oss down"));

        assertThatThrownBy(() -> service.getSegmentPreview("asset-5:text:1", "token-a"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Failed to sign preview URL");
    }

    private Segment buildImageOcrSegment() {
        return Segment.builder()
                .segmentId("asset-1:ocr:0")
                .assetId("asset-1")
                .assetType(KbAssetTypeEnum.IMAGE)
                .segmentType(SegmentType.IMAGE_OCR_BLOCK)
                .title("image-a.png")
                .ocrText("设备故障代码 E102")
                .sourceRef("oss://image-a.png")
                .thumbnail("oss://image-a.png")
                .ocrSummary("设备故障代码 E102")
                .bbox(Bbox.builder()
                        .x(120)
                        .y(80)
                        .width(360)
                        .height(48)
                        .unit("PIXEL")
                        .build())
                .imageWidth(1920)
                .imageHeight(1080)
                .build();
    }

    private Segment textSegment(String segmentId, String sourceRef) {
        return Segment.builder()
                .segmentId(segmentId)
                .assetId(segmentId.split(":")[0])
                .assetType(KbAssetTypeEnum.TEXT)
                .segmentType(SegmentType.TEXT_CHUNK)
                .title("text asset")
                .contentText("text content")
                .sourceRef(sourceRef)
                .pageNo(1)
                .chunkOrder(1)
                .build();
    }
}
