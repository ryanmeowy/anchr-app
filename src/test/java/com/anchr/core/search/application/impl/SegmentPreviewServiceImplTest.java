package com.anchr.core.search.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.application.ActivityQueryService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.search.application.support.PreviewAccessCache;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SegmentPreviewServiceImplTest {

    @Mock
    private SegmentRepository segmentRepository;
    private PreviewAccessCache previewAccessCache;
    @Mock
    private SearchObjectStoragePort objectStoragePort;
    @Mock
    private ActivityEventService activityEventService;
    @Mock
    private ActivityQueryService activityQueryService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private SegmentPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN", "token-hash-a"));
        previewAccessCache = new PreviewAccessCache();
        service = new SegmentPreviewServiceImpl(
                segmentRepository,
                objectStoragePort,
                previewAccessCache,
                activityEventService,
                activityQueryService,
                knowledgeBaseService);
        when(knowledgeBaseService.get(anyString())).thenReturn(KnowledgeBase.builder().id("kb-1").name("KB").build());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void getSegmentPreview_shouldPreferOriginalContentAndRecordIt() {
        Segment segment = segment("Original content", "OCR content", "Title");
        when(segmentRepository.findBySegmentId("seg-1")).thenReturn(Optional.of(segment));

        var result = service.getSegmentPreview("seg-1", request());

        assertThat(result.getContent()).isEqualTo("Original content");
        assertThat(result.getCitationContext().getCitationReason()).isEqualTo("预先生成的引用理由");
        ArgumentCaptor<ActivityEventService.CitationContext> captor =
                ArgumentCaptor.forClass(ActivityEventService.CitationContext.class);
        verify(activityEventService).recordCitationOpened(captor.capture());
        assertThat(captor.getValue().snippet()).isEqualTo("Original content");
        assertThat(captor.getValue().citationReason()).isEqualTo("预先生成的引用理由");
        assertThat(captor.getValue().anchor().getPageNo()).isEqualTo(2);
        assertThat(captor.getValue().anchor().getChunkOrder()).isEqualTo(7);
        assertThat(captor.getValue().anchor().getBbox()).hasSize(1);
        assertThat(captor.getValue().chunks()).extracting(CitationChunkSnapshotDTO::getSegmentId)
                .containsExactly("seg-1", "seg-2");
    }

    @Test
    void getSegmentPreview_shouldFallbackToOcrThenTitle() {
        when(segmentRepository.findBySegmentId("seg-1"))
                .thenReturn(Optional.of(segment(null, "OCR content", "Title")))
                .thenReturn(Optional.of(segment(null, null, "Title")));

        assertThat(service.getSegmentPreview("seg-1", request()).getContent()).isEqualTo("OCR content");
        assertThat(service.getSegmentPreview("seg-1", request()).getContent()).isEqualTo("Title");
    }

    @Test
    void getSegmentPreview_shouldAllowDirectPreviewWithoutCitationContext() {
        when(segmentRepository.findBySegmentId("seg-1"))
                .thenReturn(Optional.of(segment("Original content", null, "Title")));

        var result = service.getSegmentPreview("seg-1", new PreviewRequestDTO());

        assertThat(result.getContent()).isEqualTo("Original content");
        verifyNoInteractions(activityEventService);
    }

    @Test
    void getSegmentPreview_shouldPreferPersistedAnchorForRecentCitation() {
        Segment currentSegment = segment("Current content", null, "Current title");
        when(segmentRepository.findBySegmentId("seg-1")).thenReturn(Optional.of(currentSegment));
        PreviewAnchorDTO persistedAnchor = PreviewAnchorDTO.builder()
                .pageNo(9)
                .chunkOrder(42)
                .bbox(List.of(BboxInfo.builder()
                        .pageNo(9)
                        .bbox(BboxInfo.Bbox.builder().l(1).t(2).r(3).b(4).build())
                        .build()))
                .imageWidth(1200)
                .imageHeight(1600)
                .build();
        when(activityQueryService.fetchCitationsById("record-1")).thenReturn(RecentCitationDTO.builder()
                .recordId("record-1")
                .segmentId("seg-1")
                .citationReason("历史引用理由")
                .citationIndex("1")
                .anchor(persistedAnchor)
                .build());
        PreviewRequestDTO request = new PreviewRequestDTO();
        request.setRecordId("record-1");

        var result = service.getSegmentPreview("seg-1", request);

        assertThat(result.getAnchor()).isSameAs(persistedAnchor);
        assertThat(result.getAnchor().getPageNo()).isEqualTo(9);
        assertThat(result.getAnchor().getChunkOrder()).isEqualTo(42);
        assertThat(result.getCitationContext().getCitationReason()).isEqualTo("历史引用理由");
        verifyNoInteractions(activityEventService);
    }

    @Test
    void getSegmentPreview_shouldReuseSignedUrlForSegmentsOfSameAsset() {
        Segment first = Segment.builder()
                .segmentId("seg-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .contentText("First")
                .title("Title")
                .sourceRef("oss://documents/shared.pdf")
                .build();
        Segment second = Segment.builder()
                .segmentId("seg-2")
                .kbId("kb-1")
                .assetId("asset-1")
                .contentText("Second")
                .title("Title")
                .sourceRef("oss://documents/shared.pdf")
                .build();
        long expiresAt = System.currentTimeMillis() + 120_000L;
        when(segmentRepository.findBySegmentId("seg-1")).thenReturn(Optional.of(first));
        when(segmentRepository.findBySegmentId("seg-2")).thenReturn(Optional.of(second));
        when(objectStoragePort.buildPreviewUrl("documents/shared.pdf"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl("https://preview", expiresAt));

        var firstResult = service.getSegmentPreview("seg-1", new PreviewRequestDTO());
        var secondResult = service.getSegmentPreview("seg-2", new PreviewRequestDTO());

        assertThat(firstResult.getPreviewUrl()).isEqualTo("https://preview");
        assertThat(secondResult.getPreviewUrl()).isEqualTo("https://preview");
        verify(objectStoragePort).buildPreviewUrl("documents/shared.pdf");
    }

    @Test
    void documentImageShouldUseAssetForDocumentPreviewAndSourceRefForImagePreview() {
        Segment segment = Segment.builder()
                .segmentId("seg-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .assetType("PDF")
                .segmentType(SegmentType.DOCUMENT_IMAGE)
                .title("Architecture")
                .contentText("architecture diagram")
                .sourceRef("embedded/architecture.png")
                .build();
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("design.pdf")
                .objectKey("documents/design.pdf")
                .previewObjectKey("previews/design.pdf")
                .build();
        long expiresAt = System.currentTimeMillis() + 120_000L;
        when(segmentRepository.findBySegmentId("seg-1"))
                .thenReturn(Optional.of(segment));
        when(knowledgeBaseService.getDocument("kb-1", "asset-1"))
                .thenReturn(asset);
        when(objectStoragePort.buildPreviewUrl("previews/design.pdf"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl(
                        "https://preview/document", expiresAt));
        when(objectStoragePort.buildPreviewUrl("embedded/architecture.png"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl(
                        "https://preview/image", expiresAt));

        var result = service.getSegmentPreview("seg-1", new PreviewRequestDTO());

        assertThat(result.getFileName()).isEqualTo("design.pdf");
        assertThat(result.getPreviewUrl()).isEqualTo("https://preview/document");
        assertThat(result.getImagePreviewUrl()).isEqualTo("https://preview/image");
        assertThat(result.getSourceRef()).isEqualTo("embedded/architecture.png");
    }

    private Segment segment(String content, String ocr, String title) {
        return Segment.builder()
                .segmentId("seg-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .contentText(content)
                .ocrText(ocr)
                .title(title)
                .pageNo(2)
                .chunkOrder(7)
                .bbox(List.of(BboxInfo.builder()
                        .pageNo(2)
                        .bbox(BboxInfo.Bbox.builder().l(10).t(20).r(30).b(40).build())
                        .build()))
                .build();
    }

    private PreviewRequestDTO request() {
        PreviewRequestDTO request = new PreviewRequestDTO();
        request.setSourceType("ASK");
        request.setSourceId("turn-1");
        request.setSessionId("session-1");
        request.setQuestion("question");
        PreviewRequestDTO.CitationInfo citationInfo = new PreviewRequestDTO.CitationInfo();
        citationInfo.setCitationIndex("1");
        citationInfo.setReason("预先生成的引用理由");
        citationInfo.setChunks(List.of(
                CitationChunkSnapshotDTO.builder().segmentId("seg-1").content("Original content").build(),
                CitationChunkSnapshotDTO.builder().segmentId("seg-2").content("Other content").build()
        ));
        request.setCitationInfo(citationInfo);
        return request;
    }
}
