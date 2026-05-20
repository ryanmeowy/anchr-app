package com.anchr.core.search.domain.strategy.impl;

import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.ranking.DualRouteRrfFusionService;
import com.anchr.core.search.domain.repository.ImageSearchRepository;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridDualRetrievalStrategyTest {

    @Mock
    private ImageSearchRepository imageSearchRepository;
    @Mock
    private SearchRerankPort searchRerankPort;

    private HybridDualRetrievalStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new HybridDualRetrievalStrategy(
                imageSearchRepository,
                new DualRouteRrfFusionService(),
                searchRerankPort,
                new SimpleMeterRegistry(),
                new AppSearchProperties()
        );
        ReflectionTestUtils.setField(strategy, "rrfRankConstant", 60);
        ReflectionTestUtils.setField(strategy, "rrfCandidateMultiplier", 4);
        ReflectionTestUtils.setField(strategy, "rrfMaxCandidates", 200);
        ReflectionTestUtils.setField(strategy, "rerankEnabled", false);
    }

    @Test
    void search_shouldUseVectorAndTextQueriesForHybrid() {
        SearchQueryDTO query = new SearchQueryDTO();
        query.setKeyword("cat");
        query.setTopK(5);
        query.setLimit(3);
        query.setEnableOcr(true);

        List<ImageSearchResultDTO> vectorHits = List.of(
                hit(1L, 0.91d, Map.of(), List.of()),
                hit(2L, 0.90d, Map.of(), List.of()),
                hit(3L, 0.89d, Map.of(), List.of())
        );
        List<ImageSearchResultDTO> textHits = List.of(
                hit(2L, 0.88d, Map.of("ocrContent", "a <em>cat</em> on sofa"), List.of("ocrContent")),
                hit(3L, 0.87d, Map.of("fileName", "<em>cat</em>.jpg"), List.of("fileName")),
                hit(4L, 0.86d, Map.of("tags", "<em>cat</em>"), List.of("tags"))
        );

        when(imageSearchRepository.vectorSearch(anyList(), eq(12))).thenReturn(vectorHits);
        when(imageSearchRepository.textSearch(eq("cat"), eq(12), eq(true))).thenReturn(textHits);

        List<ImageSearchResultDTO> results = strategy.search(query, List.of(0.1f, 0.2f));

        verify(imageSearchRepository, times(1)).vectorSearch(anyList(), eq(12));
        verify(imageSearchRepository, times(1)).textSearch(eq("cat"), eq(12), eq(true));

        assertThat(results).hasSize(3);
        assertThat(results).extracting(item -> item.getDocument().getId())
                .containsExactly(2L, 3L, 1L);
        assertThat(results.get(0).getVectorRecallHit()).isTrue();
        assertThat(results.get(0).getTextRecallHit()).isTrue();
        assertThat(results.get(0).getHighlightFields()).containsExactly("ocrContent");
    }

    private ImageSearchResultDTO hit(Long id, double score, Map<String, String> highlights, List<String> highlightFields) {
        ImageDocument doc = new ImageDocument();
        doc.setId(id);
        doc.setFileName("f-" + id + ".jpg");
        return ImageSearchResultDTO.builder()
                .document(doc)
                .rawScore(score)
                .score(score)
                .highlights(highlights)
                .highlightFields(highlightFields)
                .build();
    }
}
