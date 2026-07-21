package com.anchr.core.search.application.impl;

import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedSearchTopChunkMappingTest {

    @Test
    void toTopChunk_shouldKeepOriginalContentAndDocumentPosition() {
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                null, null, null, null, null, null, null);
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
                null, null, null, rerankPort, new AppSearchProperties(), meterRegistry, null);
        List<SegmentRerankCandidate> candidates = List.of(
                new SegmentRerankCandidate("s1", null, null, 0.9D, 0.9D, 2, true),
                new SegmentRerankCandidate("s2", null, null, 0.8D, 0.8D, 1, false));

        Object outcome = ReflectionTestUtils.invokeMethod(service, "applyRerank", "query", candidates, 2);

        assertThat(outcome).isNotNull();
        assertThat(ReflectionTestUtils.getField(outcome, "candidates")).isEqualTo(candidates);
        assertThat(meterRegistry.counter("kb.search.rerank.fallback", "reason", "model_error").count())
                .isEqualTo(1.0D);
    }
}
