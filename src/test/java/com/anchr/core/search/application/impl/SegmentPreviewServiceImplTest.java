package com.anchr.core.search.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.application.ActivityQueryService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.search.application.SearchCitationReasonService;
import com.anchr.core.search.application.support.PreviewAccessCache;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
    private SearchCitationReasonService citationReasonService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private SegmentPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER", "token-hash-a"));
        previewAccessCache = new PreviewAccessCache();
        service = new SegmentPreviewServiceImpl(
                segmentRepository,
                objectStoragePort,
                previewAccessCache,
                activityEventService,
                activityQueryService,
                citationReasonService,
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
        ArgumentCaptor<ActivityEventService.CitationContext> captor =
                ArgumentCaptor.forClass(ActivityEventService.CitationContext.class);
        verify(activityEventService).recordCitationOpened(captor.capture());
        assertThat(captor.getValue().snippet()).isEqualTo("Original content");
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

    private Segment segment(String content, String ocr, String title) {
        return Segment.builder()
                .segmentId("seg-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .contentText(content)
                .ocrText(ocr)
                .title(title)
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
        request.setCitationInfo(citationInfo);
        return request;
    }
}
