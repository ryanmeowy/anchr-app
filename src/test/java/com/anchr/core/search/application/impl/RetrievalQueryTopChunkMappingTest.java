package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalQueryTopChunkMappingTest {

    @Test
    void visualProjectionShouldRemainAResultWithoutPretendingItsTitleIsEvidence() {
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                null, null, null, null, null, null);
        Segment segment = Segment.builder()
                .segmentId("visual-1")
                .assetId("asset-1")
                .segmentType(SegmentType.IMAGE_VISUAL)
                .title("architecture.png")
                .sourceRef("images/architecture.png")
                .build();
        SegmentRerankCandidate candidate = new SegmentRerankCandidate(
                "visual-1",
                segment,
                Map.of("title", "architecture.png"),
                0.9D,
                0.9D,
                1,
                true);

        RetrievalHit result = ReflectionTestUtils.invokeMethod(
                service, "toResult", candidate, "architecture");

        assertThat(result).isNotNull();
        assertThat(result.segmentType())
                .isEqualTo(SegmentType.IMAGE_VISUAL.name());
        assertThat(result.title()).isEqualTo("architecture.png");
        assertThat(result.sourceRef())
                .isEqualTo("images/architecture.png");
        assertThat(result.content()).isEmpty();
        assertThat(result.snippet()).isEmpty();
    }

    @Test
    void toTopChunk_shouldKeepOriginalContentAndDocumentPosition() {
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                null, null, null, null, null, null);
        RetrievalHit segment = new RetrievalHit(
                null, "2.1 Retrieval", "full original content", null, null, "short snippet",
                3, null, new RetrievalExplain(List.of("OCR"), null, null, null),
                new RetrievalAnchor(3, 12, List.of(), null, null), null, null, null, List.of(),
                "seg-1", null, null, null, null, null);

        RetrievalTopChunk topChunk = ReflectionTestUtils.invokeMethod(service, "toTopChunk", segment);

        assertThat(topChunk).isNotNull();
        assertThat(topChunk.title()).isEqualTo("2.1 Retrieval");
        assertThat(topChunk.content()).isEqualTo("full original content");
        assertThat(topChunk.anchor().chunkOrder()).isEqualTo(12);
        assertThat(topChunk.explain().hitSources())
                .containsExactly("OCR");
    }

    @Test
    void documentImagePreviewShouldBeSignedFromSourceRef() {
        SearchObjectStoragePort objectStoragePort = mock(SearchObjectStoragePort.class);
        when(objectStoragePort.buildPreviewUrl("embedded/diagram.png"))
                .thenReturn(new SearchObjectStoragePort.SignedObjectUrl(
                        "https://preview/diagram", 123L));
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                null, null, null, null, null, null);
        service.setObjectStoragePort(objectStoragePort);
        Segment segment = Segment.builder()
                .segmentId("document-image-1")
                .segmentType(SegmentType.DOCUMENT_IMAGE)
                .sourceRef("embedded/diagram.png")
                .contentText("diagram")
                .build();
        SegmentRerankCandidate candidate = new SegmentRerankCandidate(
                "document-image-1", segment, Map.of(), 0.9D, 0.9D, 1, true);

        RetrievalHit result = ReflectionTestUtils.invokeMethod(
                service, "toResult", candidate, "diagram");

        assertThat(result.imagePreviewUrl()).isEqualTo("https://preview/diagram");
        verify(objectStoragePort).buildPreviewUrl("embedded/diagram.png");
    }

    @Test
    void applyRerank_shouldRetainRrfOrderWhenModelThrows() {
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        when(rerankPort.rerank(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("timeout"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                null, null, null, rerankPort, new AppSearchProperties(), meterRegistry);
        List<SegmentRerankCandidate> candidates = List.of(
                new SegmentRerankCandidate("s1", null, null, 0.9D, 0.9D, 2, true),
                new SegmentRerankCandidate("s2", null, null, 0.8D, 0.8D, 1, false));

        Object outcome = ReflectionTestUtils.invokeMethod(service, "applyRerank", "query", candidates, 2);

        assertThat(outcome).isNotNull();
        assertThat(ReflectionTestUtils.getField(outcome, "candidates")).isEqualTo(candidates);
        assertThat(meterRegistry.counter("kb.search.rerank.fallback", "reason", "model_error").count())
                .isEqualTo(1.0D);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generationGate_shouldKeepOnlyActiveCandidatesWithoutChangingTheirOrderOrScore() {
        SearchKnowledgeAcl searchKnowledgeAcl = mock(SearchKnowledgeAcl.class);
        when(searchKnowledgeAcl.findActiveIndexGenerations(anyCollection()))
                .thenReturn(Map.of("asset-active", 2L, "asset-legacy", 0L));
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                null, null, searchKnowledgeAcl, null, null, null);
        SegmentRerankCandidate active = candidate("active", "asset-active", 2L, 0.7D);
        SegmentRerankCandidate pending = candidate("pending", "asset-active", 3L, 0.9D);
        SegmentRerankCandidate legacy = candidate("legacy", "asset-legacy", 0L, 0.6D);
        SegmentRerankCandidate deleted = candidate("deleted", "asset-deleted", 1L, 0.8D);

        List<SegmentRerankCandidate> visible = ReflectionTestUtils.invokeMethod(
                service, "filterActiveIndexGeneration",
                List.of(active, pending, legacy, deleted));

        assertThat(visible).containsExactly(active, legacy);
        assertThat(visible).extracting(SegmentRerankCandidate::score)
                .containsExactly(0.7D, 0.6D);
        verify(searchKnowledgeAcl).findActiveIndexGenerations(anyCollection());
    }

    private SegmentRerankCandidate candidate(
            String segmentId,
            String assetId,
            long generation,
            double score
    ) {
        Segment segment = Segment.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .indexGeneration(generation)
                .build();
        return new SegmentRerankCandidate(
                segmentId, segment, Map.of(), score, score, 1, false);
    }
}
