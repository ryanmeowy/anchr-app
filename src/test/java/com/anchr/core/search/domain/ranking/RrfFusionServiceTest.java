package com.anchr.core.search.domain.ranking;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionServiceTest {

    private final RrfFusionService service = new RrfFusionService();

    @Test
    void fuse_shouldRankWithinHybridCandidatesUsingOtherListsSignals() {
        List<ImageSearchResultDTO> hybrid = List.of(
                hit(1L, 0.95),
                hit(2L, 0.90)
        );
        List<ImageSearchResultDTO> text = List.of(
                hit(3L, 0.99),
                hit(2L, 0.45)
        );

        List<ImageSearchResultDTO> fused = service.fuse(List.of(hybrid, text), 3, 60);

        assertThat(fused).hasSize(2);
        assertThat(fused.getFirst().getDocument().getId()).isEqualTo(2L);
        assertThat(fused).extracting(x -> x.getDocument().getId()).containsExactly(2L, 1L);
    }

    @Test
    void fuse_shouldDeduplicateAndRespectLimit() {
        List<ImageSearchResultDTO> a = List.of(hit(1L, 0.9), hit(2L, 0.8));
        List<ImageSearchResultDTO> b = List.of(hit(2L, 0.7), hit(3L, 0.6));

        List<ImageSearchResultDTO> fused = service.fuse(List.of(a, b), 2, 60);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(x -> x.getDocument().getId()).doesNotHaveDuplicates();
    }

    @Test
    void fuse_shouldKeepHybridHighlightsEvenWhenPrimaryComesFromAnotherList() {
        List<ImageSearchResultDTO> hybrid = List.of(
                hit(1L, 0.80, Map.of("tags", "<em>cat</em>"), List.of("tags")),
                hit(2L, 0.70)
        );
        List<ImageSearchResultDTO> vector = List.of(
                hit(1L, 0.95),
                hit(3L, 0.90)
        );

        List<ImageSearchResultDTO> fused = service.fuse(List.of(hybrid, vector), 3, 60);

        ImageSearchResultDTO doc1 = fused.stream()
                .filter(item -> item.getDocument() != null && Long.valueOf(1L).equals(item.getDocument().getId()))
                .findFirst()
                .orElseThrow();
        assertThat(doc1.getHighlights()).containsEntry("tags", "<em>cat</em>");
        assertThat(doc1.getHighlightFields()).contains("tags");
    }

    @Test
    void fuse_shouldNotUseOtherListsHighlightsWhenHybridHasNone() {
        List<ImageSearchResultDTO> hybrid = List.of(
                hit(1L, 0.80),
                hit(2L, 0.70)
        );
        List<ImageSearchResultDTO> text = List.of(
                hit(1L, 0.60, Map.of("ocrContent", "a <em>cat</em> on sofa"), List.of("ocrContent")),
                hit(3L, 0.90)
        );

        List<ImageSearchResultDTO> fused = service.fuse(List.of(hybrid, text), 3, 60);

        ImageSearchResultDTO doc1 = fused.stream()
                .filter(item -> item.getDocument() != null && Long.valueOf(1L).equals(item.getDocument().getId()))
                .findFirst()
                .orElseThrow();
        assertThat(doc1.getHighlights()).isNullOrEmpty();
        assertThat(doc1.getHighlightFields()).isNullOrEmpty();
    }

    private ImageSearchResultDTO hit(Long id, double rawScore) {
        return hit(id, rawScore, null, null);
    }

    private ImageSearchResultDTO hit(Long id,
                                     double rawScore,
                                     Map<String, String> highlights,
                                     List<String> highlightFields) {
        ImageDocument document = new ImageDocument();
        document.setId(id);
        document.setFileName("img-" + id + ".jpg");
        return ImageSearchResultDTO.builder()
                .document(document)
                .rawScore(rawScore)
                .score(rawScore)
                .highlights(highlights)
                .highlightFields(highlightFields)
                .build();
    }
}
