package com.anchr.core.search.interfaces.assembler;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageDocConvertorTest {

    @Mock
    private SearchObjectStoragePort objectStoragePort;

    @Test
    void convert2SearchResultDTO_shouldMapHighlightsFromDomainResult() {
        ImageDocConvertor convertor = new ImageDocConvertor(objectStoragePort);
        ImageDocument document = new ImageDocument();
        document.setId(42L);
        document.setFileName("cat.png");
        document.setImagePath("images/cat.png");
        document.setTags(List.of("cat"));

        ImageSearchResultDTO source = ImageSearchResultDTO.builder()
                .document(document)
                .score(0.9d)
                .sortValues(List.of("42"))
                .highlights(Map.of("ocrContent", "ocr <em>cat</em> snippet"))
                .build();

        when(objectStoragePort.buildDisplayImageUrl("images/cat.png"))
                .thenReturn("https://example.com/cat.png");

        List<SearchResultDTO> results = convertor.convert2SearchResultDTO(List.of(source));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo("42");
        assertThat(results.getFirst().getUrl()).isEqualTo("https://example.com/cat.png");
        assertThat(results.getFirst().getHighlights()).containsEntry("ocrContent", "ocr <em>cat</em> snippet");
    }
}
