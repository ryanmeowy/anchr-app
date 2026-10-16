package com.anchr.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization of the strict-then-relaxed keyword recall floor: the strict pass keeps its
 * ranking precedence and a relaxed pass only runs when it cannot fill the requested page.
 */
@ExtendWith(MockitoExtension.class)
class EsSegmentRelaxedRecallTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private SegmentIndexManager indexManager;

    @Test
    void strictRecallBelowLimitShouldAppendRelaxedHitsAfterStrictHits() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response(hit("seg-strict", 9.0));
        SearchResponse<SegmentDocument> relaxedResponse =
                response(hit("seg-strict", 9.0), hit("seg-relaxed", 42.63));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenReturn(relaxedResponse);

        List<SegmentHit> hits = repository().textSearch("完整问题", List.of("关键词"), 5, null);

        assertThat(hits).extracting(hit -> hit.getSegment().getSegmentId())
                .containsExactly("seg-strict", "seg-relaxed");
        ArgumentCaptor<SearchRequest> requests = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(2)).search(requests.capture(), eq(SegmentDocument.class));
        SearchRequest strict = requests.getAllValues().get(0);
        SearchRequest relaxed = requests.getAllValues().get(1);
        assertThat(keywordMatch(strict)).isEqualTo("70%");
        assertThat(overallMatch(strict)).isEqualTo("2<70%");
        assertThat(keywordMatch(relaxed)).isEqualTo("50%");
        assertThat(overallMatch(relaxed)).isEqualTo("2<50%");
    }

    @Test
    void strictRecallFillingThePageShouldSkipTheRelaxedPass() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response(hit("seg-1", 5.0), hit("seg-2", 4.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse);

        List<SegmentHit> hits = repository().textSearch("完整问题", List.of("关键词"), 2, null);

        assertThat(hits).hasSize(2);
        verify(client, times(1)).search(any(SearchRequest.class), eq(SegmentDocument.class));
    }

    @Test
    void relaxedPassFailureShouldDegradeToStrictHits() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response(hit("seg-strict", 9.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenThrow(new IOException("relaxed query rejected"));

        List<SegmentHit> hits = repository().textSearch("完整问题", List.of("关键词"), 5, null);

        assertThat(hits).extracting(hit -> hit.getSegment().getSegmentId())
                .containsExactly("seg-strict");
    }

    private String keywordMatch(SearchRequest request) {
        return request.query().bool().should().getFirst().combinedFields().minimumShouldMatch();
    }

    private String overallMatch(SearchRequest request) {
        return request.query().bool().minimumShouldMatch();
    }

    @SafeVarargs
    private SearchResponse<SegmentDocument> response(Hit<SegmentDocument>... hits) {
        @SuppressWarnings("unchecked")
        SearchResponse<SegmentDocument> response = mock(SearchResponse.class, RETURNS_DEEP_STUBS);
        when(response.hits().hits()).thenReturn(List.of(hits));
        return response;
    }

    private Hit<SegmentDocument> hit(String segmentId, double score) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(segmentId);
        return Hit.of(hit -> hit.index("kb_segment_read").id(segmentId).score(score).source(document));
    }

    private EsSegmentRepository repository() {
        return new EsSegmentRepository(
                client,
                indexManager,
                new SegmentIndexWriteBarrier(),
                new SegmentRebuildMutationTracker());
    }

    private SegmentIndexStatusDTO readable() {
        return SegmentIndexStatusDTO.builder()
                .status(SegmentIndexStatus.READY)
                .indexExists(true)
                .readable(true)
                .writable(true)
                .build();
    }
}
