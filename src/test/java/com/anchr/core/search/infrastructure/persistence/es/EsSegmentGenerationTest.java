package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.infrastructure.persistence.es.repository.EsSegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsSegmentGenerationTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private SegmentIndexManager indexManager;

    @Test
    void generationZeroDelete_shouldMatchExplicitZeroAndLegacyMissingField()
            throws IOException {
        EsSegmentRepository repository = repository();
        when(indexManager.status()).thenReturn(writable());
        DeleteByQueryResponse response = Mockito.mock(
                DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.versionConflicts()).thenReturn(0L);
        when(response.failures()).thenReturn(List.of());
        when(client.deleteByQuery(any(DeleteByQueryRequest.class)))
                .thenReturn(response);

        repository.deleteByAssetGeneration("asset-1", 0L);

        ArgumentCaptor<DeleteByQueryRequest> request =
                ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        Mockito.verify(client).deleteByQuery(request.capture());
        var filters = request.getValue().query().bool().filter();
        assertThat(filters).hasSize(2);
        var generationFilter = filters.get(1).bool();
        assertThat(generationFilter.minimumShouldMatch()).isEqualTo("1");
        assertThat(generationFilter.should()).hasSize(2);
        assertThat(generationFilter.should().getFirst().term().field())
                .isEqualTo("indexGeneration");
        assertThat(generationFilter.should().get(1)
                .bool().mustNot().getFirst().exists().field())
                .isEqualTo("indexGeneration");
    }

    @Test
    void assetTextListingShouldExcludeImageVisualSegments() throws IOException {
        EsSegmentRepository repository = repository();
        when(indexManager.status()).thenReturn(writable());
        @SuppressWarnings("unchecked")
        SearchResponse<SegmentDocument> response =
                mock(SearchResponse.class, RETURNS_DEEP_STUBS);
        when(response.hits().hits()).thenReturn(List.of());
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(response);

        repository.listByAssetId(
                "kb-1", "asset-1", 3L, null, null, 20);

        ArgumentCaptor<SearchRequest> request =
                ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(request.capture(), eq(SegmentDocument.class));
        var exclusions = request.getValue().query().bool().mustNot();
        assertThat(exclusions).singleElement().satisfies(query -> {
            assertThat(query.term().field()).isEqualTo("segmentType");
            assertThat(query.term().value().stringValue())
                    .isEqualTo(SegmentType.IMAGE_VISUAL.name());
        });
    }

    private EsSegmentRepository repository() {
        return new EsSegmentRepository(
                client,
                indexManager,
                new SegmentIndexWriteBarrier(),
                new SegmentRebuildMutationTracker());
    }

    private SegmentIndexStatusDTO writable() {
        return SegmentIndexStatusDTO.builder()
                .status(SegmentIndexStatus.READY)
                .indexExists(true)
                .readable(true)
                .writable(true)
                .build();
    }
}
