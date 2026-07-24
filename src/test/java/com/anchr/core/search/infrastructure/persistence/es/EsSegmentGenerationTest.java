package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.infrastructure.persistence.es.repository.EsSegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        DeleteByQueryResponse response = org.mockito.Mockito.mock(
                DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.versionConflicts()).thenReturn(0L);
        when(response.failures()).thenReturn(List.of());
        when(client.deleteByQuery(any(DeleteByQueryRequest.class)))
                .thenReturn(response);

        repository.deleteByAssetGeneration("asset-1", 0L);

        ArgumentCaptor<DeleteByQueryRequest> request =
                ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        org.mockito.Mockito.verify(client).deleteByQuery(request.capture());
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

    private EsSegmentRepository repository() {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        return new EsSegmentRepository(
                client,
                config,
                indexManager,
                new SegmentIndexWriteBarrier());
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
