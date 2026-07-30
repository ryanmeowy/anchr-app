package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.model.RetrievalPageQuery;
import com.anchr.core.search.application.api.model.RetrievalPageResult;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalQueryServiceImplCharacterizationTest {

    @Test
    void pageQueryShouldKeepRecallRoutesGenerationGateRerankAndPageProjection() {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        AppSearchProperties properties = new AppSearchProperties();
        properties.getRrf().setCandidateMultiplier(2);
        properties.getRrf().setMaxCandidates(20);
        Segment segment = Segment.builder()
                .segmentId("seg-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .indexGeneration(2L)
                .assetType("PDF")
                .segmentType(SegmentType.TEXT_CHUNK)
                .title("Architecture")
                .contentText("retrieval architecture")
                .pageNo(1)
                .chunkOrder(2)
                .build();
        SegmentHit hit = SegmentHit.builder()
                .segment(segment)
                .rawScore(0.8D)
                .highlights(Map.of("contentText", "<em>retrieval</em> architecture"))
                .build();
        when(knowledgeAcl.resolveVisibleKbIds(List.of("kb-1"))).thenReturn(List.of("kb-1"));
        when(knowledgeAcl.findActiveIndexGenerations(anyCollection()))
                .thenReturn(Map.of("asset-1", 2L));
        when(embedding.embedQuery("retrieval")).thenReturn(List.of(0.1F));
        when(repository.textSearch(
                eq("retrieval"), eq(List.of("architecture")), eq(6), any(SearchFilter.class)))
                .thenReturn(List.of(hit));
        when(repository.vectorSearch(anyList(), anyInt(), anyFloat(), any(SearchFilter.class)))
                .thenReturn(List.of(hit), List.of());
        when(rerankPort.rerank(eq("retrieval"), anyList(), eq(1)))
                .thenReturn(List.of(new SearchRerankPort.RerankItem(0, 0.9D)));
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                repository, embedding, knowledgeAcl, rerankPort,
                properties, new SimpleMeterRegistry());

        RetrievalPageResult page = service.query(new RetrievalPageQuery(
                " retrieval ", List.of("architecture"), 3, List.of("kb-1"), List.of(),
                List.of(" pdf ", "PDF"), List.of(), 10L, 20L, "RELEVANCE"));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.segmentId()).isEqualTo("seg-1");
            assertThat(item.totalHits()).isEqualTo(1);
            assertThat(item.topChunks()).singleElement()
                    .satisfies(chunk -> assertThat(chunk.segmentId()).isEqualTo("seg-1"));
        });
        assertThat(page.facets().get("assetTypes")).singleElement()
                .satisfies(facet -> {
                    assertThat(facet.value()).isEqualTo("PDF");
                    assertThat(facet.count()).isEqualTo(1);
                });
        assertThat(page.insight().pipeline().keywordCandidates()).isEqualTo(1);
        assertThat(page.insight().pipeline().vectorCandidates()).isEqualTo(1);
        assertThat(page.insight().pipeline().fusedRetained()).isEqualTo(1);
        assertThat(page.insight().pipeline().rerankAdopted()).isEqualTo(1);

        ArgumentCaptor<SearchFilter> vectorFilters = ArgumentCaptor.forClass(SearchFilter.class);
        verify(repository, org.mockito.Mockito.times(2))
                .vectorSearch(eq(List.of(0.1F)), anyInt(), anyFloat(), vectorFilters.capture());
        assertThat(vectorFilters.getAllValues().get(0).getHitTypes())
                .doesNotContain(SegmentType.DOCUMENT_IMAGE.name());
        assertThat(vectorFilters.getAllValues().get(1).getHitTypes())
                .containsExactly(SegmentType.DOCUMENT_IMAGE.name());
        assertThat(vectorFilters.getAllValues().get(0).getAssetTypes()).containsExactly("PDF");
        InOrder order = inOrder(embedding, repository, knowledgeAcl, rerankPort);
        order.verify(embedding).embedQuery("retrieval");
        order.verify(repository).textSearch(
                eq("retrieval"), eq(List.of("architecture")), eq(6), any(SearchFilter.class));
        order.verify(repository, org.mockito.Mockito.times(2))
                .vectorSearch(eq(List.of(0.1F)), anyInt(), anyFloat(), any(SearchFilter.class));
        order.verify(knowledgeAcl).findActiveIndexGenerations(anyCollection());
        order.verify(rerankPort).rerank(eq("retrieval"), anyList(), eq(1));
    }

    @Test
    void emptyVisibleScopeShouldReturnEmptyPageBeforeEmbeddingOrRepositoryCalls() {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        when(knowledgeAcl.resolveVisibleKbIds(List.of("missing"))).thenReturn(List.of());
        RetrievalQueryServiceImpl service = new RetrievalQueryServiceImpl(
                repository, embedding, knowledgeAcl, rerankPort,
                new AppSearchProperties(), new SimpleMeterRegistry());

        RetrievalPageResult page = service.query(new RetrievalPageQuery(
                "query", List.of(), 5, List.of("missing"), List.of(),
                List.of(), List.of(), null, null, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.facets()).containsOnlyKeys("assetTypes", "hitTypes");
        assertThat(page.insight().pipeline().fusedRetained()).isZero();
        verify(embedding, never()).embedQuery(any());
        verify(repository, never()).textSearch(any(), anyList(), anyInt(), any());
        verify(repository, never()).vectorSearch(anyList(), anyInt(), anyFloat(), any());
        verify(knowledgeAcl, never()).findActiveIndexGenerations(anyCollection());
        verify(rerankPort, never()).rerank(any(), anyList(), anyInt());
    }
}
