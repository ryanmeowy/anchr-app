package com.anchr.core.search.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import com.anchr.core.search.application.acl.SearchActivityAcl;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.support.PreviewAccessCache;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
    private SearchActivityAcl activityEventService;
    @Mock
    private SearchKnowledgeAcl searchKnowledgeAcl;

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
                searchKnowledgeAcl);
        lenient().when(searchKnowledgeAcl.findActiveKnowledgeBase(anyString()))
                .thenReturn(Optional.of(new KnowledgeBaseSummary("kb-1", "KB", "ACTIVE")));
        lenient().when(searchKnowledgeAcl.findActiveDocument(anyString(), anyString()))
                .thenReturn(Optional.of(document("asset-1", null, null, null, 0L)));
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
        ArgumentCaptor<PreviewSegmentDTO> previewCaptor =
                ArgumentCaptor.forClass(PreviewSegmentDTO.class);
        ArgumentCaptor<PreviewRequestDTO> requestCaptor = ArgumentCaptor.forClass(PreviewRequestDTO.class);
        verify(activityEventService).recordCitationOpened(previewCaptor.capture(), requestCaptor.capture());
        assertThat(previewCaptor.getValue().getContent()).isEqualTo("Original content");
        assertThat(previewCaptor.getValue().getCitationContext().getCitationReason()).isEqualTo("预先生成的引用理由");
        assertThat(previewCaptor.getValue().getAnchor().getPageNo()).isEqualTo(2);
        assertThat(previewCaptor.getValue().getAnchor().getChunkOrder()).isEqualTo(7);
        assertThat(previewCaptor.getValue().getAnchor().getBbox()).hasSize(1);
        assertThat(requestCaptor.getValue().getCitationInfo().getChunks()).extracting(CitationChunkSnapshotDTO::getSegmentId)
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
        when(activityEventService.findCitationById("record-1")).thenReturn(new SearchActivityAcl.CitationSnapshot(
                null, null, null, "1", "历史引用理由", null, persistedAnchor));
        PreviewRequestDTO request = new PreviewRequestDTO();
        request.setRecordId("record-1");

        var result = service.getSegmentPreview("seg-1", request);

        assertThat(result.getAnchor()).isSameAs(persistedAnchor);
        assertThat(result.getAnchor().getPageNo()).isEqualTo(9);
        assertThat(result.getAnchor().getChunkOrder()).isEqualTo(42);
        assertThat(result.getCitationContext().getCitationReason()).isEqualTo("历史引用理由");
        verify(activityEventService).findCitationById("record-1");
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
        DocumentSummary asset = document(
                "asset-1", "design.pdf", "documents/design.pdf", "previews/design.pdf", 0L);
        long expiresAt = System.currentTimeMillis() + 120_000L;
        when(segmentRepository.findBySegmentId("seg-1"))
                .thenReturn(Optional.of(segment));
        when(searchKnowledgeAcl.findActiveDocument("kb-1", "asset-1"))
                .thenReturn(Optional.of(asset));
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

    @Test
    void oldGenerationShouldNotBePreviewable() {
        Segment segment = segment("Old content", null, "Old title").toBuilder()
                .indexGeneration(3L)
                .build();
        when(segmentRepository.findBySegmentId("seg-1")).thenReturn(Optional.of(segment));
        when(searchKnowledgeAcl.findActiveDocument("kb-1", "asset-1")).thenReturn(
                Optional.of(document("asset-1", null, null, null, 4L)));

        assertThatThrownBy(() -> service.getSegmentPreview("seg-1", new PreviewRequestDTO()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getError())
                .isEqualTo(ApiError.SEGMENT_NOT_FOUND);

        verifyNoInteractions(objectStoragePort, activityEventService);
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

    private DocumentSummary document(String id, String fileName, String objectKey,
                                     String previewObjectKey, long generation) {
        return new DocumentSummary(
                id, "kb-1", fileName, null, null, null,
                objectKey, previewObjectKey, generation, 0);
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
