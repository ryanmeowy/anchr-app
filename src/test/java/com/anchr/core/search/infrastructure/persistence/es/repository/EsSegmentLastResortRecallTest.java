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
 * Characterization of the last-resort recall floor: when the rewrite produced no keyword at all,
 * the long question is the only text clause and the strict/relaxed keyword bars cannot be met, so
 * a third pass with a third-of-the-terms bar keeps the candidate pool non-empty. Keyword-centric
 * retrievals never reach that pass.
 */
@ExtendWith(MockitoExtension.class)
class EsSegmentLastResortRecallTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private SegmentIndexManager indexManager;

    @Test
    void keywordLessQueryBelowLimitShouldAppendLastResortHitsAfterMergedHits() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response();
        SearchResponse<SegmentDocument> relaxedResponse = response(hit("seg-relaxed", 20.0));
        SearchResponse<SegmentDocument> lastResortResponse =
                response(hit("seg-relaxed", 20.0), hit("seg-last", 12.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenReturn(relaxedResponse)
                .thenReturn(lastResortResponse);

        List<SegmentHit> hits = repository().textSearch("连续两次协议错误之后 Run 会进入什么终态", List.of(), 5, null);

        assertThat(hits).extracting(hit -> hit.getSegment().getSegmentId())
                .containsExactly("seg-relaxed", "seg-last");
        ArgumentCaptor<SearchRequest> requests = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(3)).search(requests.capture(), eq(SegmentDocument.class));
        assertThat(keywordMatch(requests.getAllValues().get(0))).isEqualTo("70%");
        assertThat(overallMatch(requests.getAllValues().get(0))).isEqualTo("2<70%");
        assertThat(keywordMatch(requests.getAllValues().get(1))).isEqualTo("50%");
        assertThat(overallMatch(requests.getAllValues().get(1))).isEqualTo("2<50%");
        assertThat(keywordMatch(requests.getAllValues().get(2))).isEqualTo("30%");
        assertThat(overallMatch(requests.getAllValues().get(2))).isEqualTo("1");
    }

    @Test
    void keywordLessQueryFilledByTheRelaxedPassShouldSkipTheLastResortPass() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response();
        SearchResponse<SegmentDocument> relaxedResponse =
                response(hit("seg-1", 30.0), hit("seg-2", 29.0), hit("seg-3", 28.0),
                        hit("seg-4", 27.0), hit("seg-5", 26.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenReturn(relaxedResponse);

        List<SegmentHit> hits = repository().textSearch("连续两次协议错误之后 Run 会进入什么终态", List.of(), 5, null);

        assertThat(hits).hasSize(5);
        verify(client, times(2)).search(any(SearchRequest.class), eq(SegmentDocument.class));
    }

    @Test
    void keywordQueryBelowLimitShouldNeverRunTheLastResortPass() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response(hit("seg-strict", 12.0));
        SearchResponse<SegmentDocument> relaxedResponse =
                response(hit("seg-strict", 12.0), hit("seg-relaxed", 9.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenReturn(relaxedResponse);

        List<SegmentHit> hits = repository().textSearch("完整问题", List.of("协议错误"), 5, null);

        assertThat(hits).extracting(hit -> hit.getSegment().getSegmentId())
                .containsExactly("seg-strict", "seg-relaxed");
        verify(client, times(2)).search(any(SearchRequest.class), eq(SegmentDocument.class));
    }

    @Test
    void lastResortFailureShouldDegradeToTheMergedHits() throws IOException {
        when(indexManager.status()).thenReturn(readable());
        SearchResponse<SegmentDocument> strictResponse = response(hit("seg-strict", 12.0));
        SearchResponse<SegmentDocument> relaxedResponse =
                response(hit("seg-strict", 12.0), hit("seg-relaxed", 9.0));
        when(client.search(any(SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(strictResponse)
                .thenReturn(relaxedResponse)
                .thenThrow(new IOException("last resort query rejected"));

        List<SegmentHit> hits = repository().textSearch("连续两次协议错误之后 Run 会进入什么终态", List.of(), 5, null);

        assertThat(hits).extracting(hit -> hit.getSegment().getSegmentId())
                .containsExactly("seg-strict", "seg-relaxed");
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
