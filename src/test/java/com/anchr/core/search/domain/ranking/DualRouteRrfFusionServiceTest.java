package com.anchr.core.search.domain.ranking;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DualRouteRrfFusionServiceTest {

    private final DualRouteRrfFusionService service = new DualRouteRrfFusionService();

    @Test
    void fuse_shouldMergeTwoRoutesForRankingAndDisplayMetadata() {
        List<ImageSearchResultDTO> vectorRanking = List.of(
                hit(1L, 0.91d, Map.of(), List.of()),
                hit(2L, 0.90d, Map.of(), List.of())
        );
        List<ImageSearchResultDTO> textRanking = List.of(
                hit(2L, 0.89d, Map.of("ocrContent", "a <em>cat</em> on sofa"), List.of("ocrContent")),
                hit(3L, 0.88d, Map.of("fileName", "<em>cat</em>.jpg"), List.of("fileName"))
        );

        List<ImageSearchResultDTO> fused = service.fuse(vectorRanking, textRanking, 10, 60);

        assertThat(fused).extracting(item -> item.getDocument().getId())
                .containsExactly(2L, 1L, 3L);

        ImageSearchResultDTO id2 = fused.get(0);
        assertThat(id2.getVectorRecallHit()).isTrue();
        assertThat(id2.getTextRecallHit()).isTrue();
        assertThat(id2.getHighlightFields()).containsExactly("ocrContent");

        ImageSearchResultDTO id1 = fused.get(1);
        assertThat(id1.getVectorRecallHit()).isTrue();
        assertThat(id1.getTextRecallHit()).isFalse();
        assertThat(id1.getHighlights()).isEmpty();

        ImageSearchResultDTO id3 = fused.get(2);
        assertThat(id3.getVectorRecallHit()).isFalse();
        assertThat(id3.getTextRecallHit()).isTrue();
        assertThat(id3.getHighlightFields()).containsExactly("fileName");
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

