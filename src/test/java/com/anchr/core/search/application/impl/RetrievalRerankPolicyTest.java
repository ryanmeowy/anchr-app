package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.model.SearchRuntimeSettings;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchRerankPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalRerankPolicyTest {

    @Test
    void rerankShouldReorderOnlyTheBoundedWindowAndPreserveDocumentPayload() {
        SearchRerankPort port = mock(SearchRerankPort.class);
        when(port.rerank(eq("query"), anyList(), anyInt()))
                .thenReturn(List.of(
                        new SearchRerankPort.RerankItem(1, 2D),
                        new SearchRerankPort.RerankItem(0, -1D),
                        new SearchRerankPort.RerankItem(99, 1D)));
        SearchRuntimeSettings settings = settings();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RetrievalRerankPolicy policy = new RetrievalRerankPolicy(port, meters);
        List<SegmentRerankCandidate> candidates = List.of(
                candidate("s1", 1D, "first"),
                candidate("s2", 0.8D, "second"),
                candidate("s3", 0.7D, "tail"));

        RetrievalRerankPolicy.Outcome outcome =
                policy.rerank("query", candidates, 2, settings);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.candidates()).extracting(SegmentRerankCandidate::segmentId)
                .containsExactly("s2", "s1", "s3");
        assertThat(outcome.candidates().get(0).score()).isEqualTo(0.88D);
        assertThat(outcome.candidates().get(1).score()).isEqualTo(0.6D);
        assertThat(outcome.candidates().get(2)).isSameAs(candidates.get(2));
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(port).rerank(eq("query"), documents.capture(), eq(2));
        assertThat(documents.getValue().getFirst())
                .isEqualTo("segmentType: TEXT_CHUNK\ntitle: first\ncontent: content-first"
                        + "\nocr: ocr-first\ntags: tag-first");
        assertThat(meters.counter("kb.search.rerank.calls").count()).isEqualTo(1D);
    }

    @Test
    void emptyProviderResultShouldKeepOriginalOrderAndRecordFallback() {
        SearchRerankPort port = mock(SearchRerankPort.class);
        when(port.rerank(eq("query"), anyList(), anyInt())).thenReturn(List.of());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RetrievalRerankPolicy policy = new RetrievalRerankPolicy(port, meters);
        List<SegmentRerankCandidate> candidates =
                List.of(candidate("s1", 1D, "first"));

        RetrievalRerankPolicy.Outcome outcome = policy.rerank("query", candidates, 1);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.candidates()).isSameAs(candidates);
        assertThat(meters.counter(
                "kb.search.rerank.fallback", "reason", "empty_result").count()).isEqualTo(1D);
    }

    private SearchRuntimeSettings settings() {
        SearchRuntimeSettings defaults = SearchRuntimeSettings.defaults();
        return new SearchRuntimeSettings(
                defaults.rankConstant(),
                defaults.candidateMultiplier(),
                defaults.maxCandidates(),
                defaults.textTopK(),
                defaults.documentImageTopK(),
                defaults.textSimilarity(),
                defaults.documentImageSimilarity(),
                defaults.maxDocChars(),
                true,
                2,
                defaults.windowFactor(),
                1,
                2,
                0.6D,
                0.4D);
    }

    private SegmentRerankCandidate candidate(String segmentId, double score, String title) {
        Segment segment = Segment.builder()
                .segmentId(segmentId)
                .segmentType(SegmentType.TEXT_CHUNK)
                .title(title)
                .contentText("content-" + title)
                .ocrText("ocr-" + title)
                .tags(List.of("tag-" + title))
                .build();
        return new SegmentRerankCandidate(
                segmentId, segment, Map.of(), score, score, 1, false);
    }
}
