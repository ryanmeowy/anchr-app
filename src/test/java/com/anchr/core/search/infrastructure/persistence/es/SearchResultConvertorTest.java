package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchResultConvertorTest {

    private final SearchResultConvertor convertor = new SearchResultConvertor();

    @Test
    void convert2Doc_shouldPreferOcrHighlight_whenMultipleFieldsExist() {
        @SuppressWarnings("unchecked")
        SearchResponse<ImageDocument> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<ImageDocument> hitsMetadata = mock(HitsMetadata.class);
        @SuppressWarnings("unchecked")
        Hit<ImageDocument> hit = mock(Hit.class);

        ImageDocument document = new ImageDocument();
        document.setFileName("cat.png");
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(document);
        when(hit.id()).thenReturn("7");
        when(hit.score()).thenReturn(1.3d);
        when(hit.sort()).thenReturn(List.of(FieldValue.of("7")));
        when(hit.highlight()).thenReturn(Map.of(
                "tags", List.of("tag <em>cat</em>"),
                "ocrContent", List.of("ocr <em>cat</em> snippet")
        ));

        List<ImageSearchResultDTO> results = convertor.convert2Doc(response);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getDocument().getId()).isEqualTo(7L);
        assertThat(results.getFirst().getHighlights())
                .containsEntry("tags", "tag <em>cat</em>")
                .containsEntry("ocrContent", "ocr <em>cat</em> snippet");
        assertThat(results.getFirst().getHighlightFields()).containsExactlyInAnyOrder("tags", "ocrContent");
    }

    @Test
    void convert2Doc_shouldKeepHighlightNull_whenNoSnippetFound() {
        @SuppressWarnings("unchecked")
        SearchResponse<ImageDocument> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<ImageDocument> hitsMetadata = mock(HitsMetadata.class);
        @SuppressWarnings("unchecked")
        Hit<ImageDocument> hit = mock(Hit.class);

        ImageDocument document = new ImageDocument();
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(document);
        when(hit.id()).thenReturn("9");
        when(hit.score()).thenReturn(0.9d);
        when(hit.sort()).thenReturn(List.of(FieldValue.of("9")));
        when(hit.highlight()).thenReturn(Map.of("tags", List.of(" ")));

        List<ImageSearchResultDTO> results = convertor.convert2Doc(response);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getHighlights()).isEmpty();
        assertThat(results.getFirst().getHighlightFields()).isEmpty();
    }
}
