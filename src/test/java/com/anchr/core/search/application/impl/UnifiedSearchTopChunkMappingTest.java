package com.anchr.core.search.application.impl;

import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
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

class UnifiedSearchTopChunkMappingTest {

    @Test
    void toTopChunk_shouldKeepOriginalContentAndDocumentPosition() {
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                null, null, null, null, null, null, null, null);
        SearchResultDTO segment = SearchResultDTO.builder()
                .segmentId("seg-1")
                .title("2.1 Retrieval")
                .content("full original content")
                .snippet("short snippet")
                .pageNo(3)
                .anchor(SearchResultDTO.Anchor.builder().pageNo(3).chunkOrder(12).build())
                .build();

        SearchResultDTO.TopChunk topChunk = ReflectionTestUtils.invokeMethod(service, "toTopChunk", segment);

        assertThat(topChunk).isNotNull();
        assertThat(topChunk.getTitle()).isEqualTo("2.1 Retrieval");
        assertThat(topChunk.getContent()).isEqualTo("full original content");
        assertThat(topChunk.getAnchor().getChunkOrder()).isEqualTo(12);
    }

    @Test
    void applyRerank_shouldRetainRrfOrderWhenModelThrows() {
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        when(rerankPort.rerank(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("timeout"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                null, null, null, null, rerankPort, new AppSearchProperties(), meterRegistry, null);
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
        AssetRepository assetRepository = mock(AssetRepository.class);
        when(assetRepository.findActiveIndexGenerations(anyCollection()))
                .thenReturn(Map.of("asset-active", 2L, "asset-legacy", 0L));
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                null, null, null, assetRepository, null, null, null, null);
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
        verify(assetRepository).findActiveIndexGenerations(anyCollection());
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
