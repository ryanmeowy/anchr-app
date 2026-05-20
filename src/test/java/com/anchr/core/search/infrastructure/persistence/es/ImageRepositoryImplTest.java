package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.domain.model.HybridSearchParamDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import com.anchr.core.search.infrastructure.persistence.es.query.factory.SearchRequestFactory;
import com.anchr.core.search.infrastructure.persistence.es.repository.ImageRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageRepositoryImplTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private SearchResultConvertor converter;
    @Mock
    private SearchRequestFactory searchRequestFactory;

    private ImageRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new ImageRepositoryImpl(esClient, converter, searchRequestFactory);
    }

    @Test
    void hybridSearch_shouldThrowIllegalState_whenEsFails() throws Exception {
        SearchRequest request = mock(SearchRequest.class);
        when(searchRequestFactory.buildHybrid(any(HybridSearchParamDTO.class))).thenReturn(request);
        when(esClient.search(eq(request), eq(ImageDocument.class))).thenThrow(new RuntimeException("es down"));

        assertThatThrownBy(() -> repository.hybridSearch(HybridSearchParamDTO.builder().build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Search backend unavailable");
    }

    @Test
    void textSearch_shouldThrowIllegalState_whenEsFails() throws Exception {
        SearchRequest request = mock(SearchRequest.class);
        when(searchRequestFactory.buildTextOnly("cat", 10, true)).thenReturn(request);
        when(esClient.search(eq(request), eq(ImageDocument.class))).thenThrow(new RuntimeException("es down"));

        assertThatThrownBy(() -> repository.textSearch("cat", 10, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Search backend unavailable");
    }

    @Test
    void findDuplicate_shouldReturnNull_whenHitsEmpty() throws Exception {
        SearchRequest request = mock(SearchRequest.class);
        @SuppressWarnings("unchecked")
        SearchResponse<ImageDocument> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<ImageDocument> hits = mock(HitsMetadata.class);
        when(searchRequestFactory.buildDuplicate(List.of(0.1f, 0.2f), 0.8d)).thenReturn(request);
        when(esClient.search(eq(request), eq(ImageDocument.class))).thenReturn(response);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of());

        ImageDocument duplicate = repository.findDuplicate(List.of(0.1f, 0.2f), 0.8d);

        assertThat(duplicate).isNull();
    }

}
