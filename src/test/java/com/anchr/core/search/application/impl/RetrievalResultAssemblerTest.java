package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalResultAssemblerTest {

    @Test
    void aggregateShouldKeepRankedPrimaryAndEveryOriginalTopChunk() {
        SearchObjectStoragePort storage = mock(SearchObjectStoragePort.class);
        when(storage.buildPreviewUrl("images/diagram.png"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl(
                        "https://preview/diagram", 123L));
        RetrievalResultAssembler assembler = new RetrievalResultAssembler(storage);
        RetrievalHit text = assembler.toResult(
                candidate("text-1", SegmentType.TEXT_CHUNK, "body", null, 0.9D),
                "body");
        RetrievalHit image = assembler.toResult(
                candidate("image-1", SegmentType.DOCUMENT_IMAGE,
                        "diagram caption", "images/diagram.png", 0.8D),
                "diagram");

        List<RetrievalHit> aggregated = assembler.aggregateByAsset(List.of(text, image), 10);

        assertThat(aggregated).singleElement().satisfies(result -> {
            assertThat(result.segmentId()).isEqualTo("text-1");
            assertThat(result.resultType()).isEqualTo("MIXED");
            assertThat(result.totalHits()).isEqualTo(2);
            assertThat(result.topChunks())
                    .extracting(chunk -> chunk.segmentId())
                    .containsExactly("text-1", "image-1");
            assertThat(result.topChunks().get(1).imagePreviewUrl())
                    .isEqualTo("https://preview/diagram");
            assertThat(result.topChunks().get(1).content()).isEqualTo("diagram caption");
        });
    }

    @Test
    void previewSigningFailureShouldKeepTheRetrievalHitWithoutPreview() {
        SearchObjectStoragePort storage = mock(SearchObjectStoragePort.class);
        when(storage.buildPreviewUrl("images/broken.png"))
                .thenThrow(new IllegalStateException("unavailable"));
        RetrievalResultAssembler assembler = new RetrievalResultAssembler(storage);

        RetrievalHit result = assembler.toResult(
                candidate("image-1", SegmentType.DOCUMENT_IMAGE,
                        "diagram", "images/broken.png", 0.8D),
                "diagram");

        assertThat(result.segmentId()).isEqualTo("image-1");
        assertThat(result.imagePreviewUrl()).isNull();
        assertThat(result.imagePreviewExpiresAt()).isNull();
    }

    private SegmentRerankCandidate candidate(
            String segmentId,
            SegmentType type,
            String content,
            String sourceRef,
            double score
    ) {
        Segment segment = Segment.builder()
                .segmentId(segmentId)
                .kbId("kb-1")
                .assetId("asset-1")
                .assetType("PDF")
                .segmentType(type)
                .title("title-" + segmentId)
                .contentText(content)
                .pageNo(2)
                .chunkOrder(3)
                .sourceRef(sourceRef)
                .build();
        return new SegmentRerankCandidate(
                segmentId, segment, Map.of(), score, score, 1, true);
    }
}
