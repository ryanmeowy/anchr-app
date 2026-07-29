package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.model.RetrievalDocumentContentQuery;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalDocumentContentQueryServiceImplTest {

    private final SegmentRepository segmentRepository = mock(SegmentRepository.class);
    private final RetrievalDocumentContentQueryServiceImpl service =
            new RetrievalDocumentContentQueryServiceImpl(segmentRepository);

    @Test
    void queriesActiveGenerationInDocumentOrderAndReturnsImmutableSnapshots() {
        Segment text = Segment.builder()
                .segmentId("seg-1").kbId("kb-1").assetId("asset-1")
                .indexGeneration(7L).assetType("DOCUMENT")
                .segmentType(SegmentType.TEXT_CHUNK).title("Section")
                .contentText("text content").ocrText("ignored ocr")
                .pageNo(2).chunkOrder(8).sourceRef("docs/guide.pdf")
                .bbox(List.of()).build();
        Segment ocr = Segment.builder()
                .segmentId("seg-2").kbId("kb-1").assetId("asset-1")
                .indexGeneration(7L).assetType("IMAGE")
                .segmentType(SegmentType.IMAGE_OCR_BLOCK).ocrText("ocr content")
                .pageNo(3).chunkOrder(9).build();
        when(segmentRepository.listByAssetId("kb-1", "asset-1", 7L, 8, "seg-1", 20))
                .thenReturn(List.of(text, ocr));

        var chunks = service.query(new RetrievalDocumentContentQuery(
                " kb-1 ", " asset-1 ", 7L, 8, " seg-1 ", 20));

        assertThat(chunks).extracting("segmentId", "content", "segmentType")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("seg-1", "text content", "TEXT_CHUNK"),
                        org.assertj.core.groups.Tuple.tuple("seg-2", "ocr content", "IMAGE_OCR_BLOCK"));
        verify(segmentRepository).listByAssetId("kb-1", "asset-1", 7L, 8, "seg-1", 20);
        assertThatThrownBy(() -> chunks.add(chunks.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidCursorAndOversizedPageBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.query(new RetrievalDocumentContentQuery(
                "kb-1", "asset-1", 7L, null, "seg-1", 101)))
                .isInstanceOf(BusinessException.class);
    }
}
