package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNResult;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalQueryServiceImplCharacterizationTest {

    @Test
    void singleAssetQueryShouldRerankMoreThanThreeTextChunksBeforeDiversifyingResult() {
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        List<SegmentHit> hits = List.of(
                hit("text-1", "asset-1", 1),
                hit("text-2", "asset-1", 2),
                hit("text-3", "asset-1", 3),
                hit("text-4", "asset-1", 4));
        when(rerankPort.rerank(eq("query"), anyList(), eq(4))).thenReturn(List.of(
                new SearchRerankPort.RerankItem(3, 1D),
                new SearchRerankPort.RerankItem(0, 0D),
                new SearchRerankPort.RerankItem(1, 0D),
                new SearchRerankPort.RerankItem(2, 0D)));
        RetrievalQueryServiceImpl service = serviceFor(
                hits, Map.of("asset-1", 1L), rerankPort);

        RetrievalTopNResult result = service.query(queryFor(List.of("asset-1")));

        verify(rerankPort).rerank(
                eq("query"),
                argThat(documents -> documents.size() == 4
                        && documents.stream().anyMatch(
                                document -> document.contains("content-text-4"))),
                eq(4));
        assertThat(result.insight().pipeline().fusedRetained()).isEqualTo(4);
        assertThat(result.insight().pipeline().rerankAdopted()).isEqualTo(4);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.totalHits()).isEqualTo(3);
            assertThat(item.topChunks())
                    .extracting(chunk -> chunk.segmentId())
                    .containsExactly("text-4", "text-1", "text-2");
        });
    }

    @Test
    void multiAssetQueryShouldStillDiversifyBeforeRerank() {
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        List<SegmentHit> hits = List.of(
                hit("asset-1-text-1", "asset-1", 1),
                hit("asset-1-text-2", "asset-1", 2),
                hit("asset-1-text-3", "asset-1", 3),
                hit("asset-1-text-4", "asset-1", 4),
                hit("asset-2-text-1", "asset-2", 1));
        when(rerankPort.rerank(eq("query"), anyList(), eq(4))).thenReturn(List.of(
                new SearchRerankPort.RerankItem(0, 1D),
                new SearchRerankPort.RerankItem(1, 0.8D),
                new SearchRerankPort.RerankItem(2, 0.6D),
                new SearchRerankPort.RerankItem(3, 0.4D)));
        RetrievalQueryServiceImpl service = serviceFor(
                hits, Map.of("asset-1", 1L, "asset-2", 1L), rerankPort);

        RetrievalTopNResult result = service.query(queryFor(List.of("asset-1", "asset-2")));

        verify(rerankPort).rerank(
                eq("query"),
                argThat(documents -> documents.size() == 4
                        && documents.stream().noneMatch(
                                document -> document.contains("content-asset-1-text-4"))
                        && documents.stream().anyMatch(
                                document -> document.contains("content-asset-2-text-1"))),
                eq(4));
        assertThat(result.insight().pipeline().fusedRetained()).isEqualTo(4);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void topNQueryShouldKeepRecallRoutesGenerationGateRerankAndWindowProjection() {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
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
        RetrievalQueryServiceImpl service = RetrievalQueryServiceTestFactory.create(
                repository, embedding, knowledgeAcl, rerankPort,
                RuntimeConfigTestUnits.values(Map.of(
                        "SEARCH.candidateMultiplier", "2",
                        "SEARCH.maxCandidates", "20")),
                new SimpleMeterRegistry());

        RetrievalTopNResult result = service.query(new RetrievalTopNQuery(
                " retrieval ", List.of("architecture"), 3, List.of("kb-1"), List.of(),
                List.of(" pdf ", "PDF"), List.of(), 10L, 20L));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.segmentId()).isEqualTo("seg-1");
            assertThat(item.totalHits()).isEqualTo(1);
            assertThat(item.topChunks()).singleElement()
                    .satisfies(chunk -> assertThat(chunk.segmentId()).isEqualTo("seg-1"));
        });
        assertThat(result.windowFacets().get("assetTypes")).singleElement()
                .satisfies(facet -> {
                    assertThat(facet.value()).isEqualTo("PDF");
                    assertThat(facet.count()).isEqualTo(1);
                });
        assertThat(result.insight().pipeline().keywordCandidates()).isEqualTo(1);
        assertThat(result.insight().pipeline().vectorCandidates()).isEqualTo(1);
        assertThat(result.insight().pipeline().fusedRetained()).isEqualTo(1);
        assertThat(result.insight().pipeline().rerankAdopted()).isEqualTo(1);

        ArgumentCaptor<SearchFilter> vectorFilters = ArgumentCaptor.forClass(SearchFilter.class);
        verify(repository, Mockito.times(2))
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
        order.verify(repository, Mockito.times(2))
                .vectorSearch(eq(List.of(0.1F)), anyInt(), anyFloat(), any(SearchFilter.class));
        order.verify(knowledgeAcl).findActiveIndexGenerations(anyCollection());
        order.verify(rerankPort).rerank(eq("retrieval"), anyList(), eq(1));
    }

    @Test
    void emptyVisibleScopeShouldReturnEmptyTopNBeforeEmbeddingOrRepositoryCalls() {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        SearchRerankPort rerankPort = mock(SearchRerankPort.class);
        when(knowledgeAcl.resolveVisibleKbIds(List.of("missing"))).thenReturn(List.of());
        RetrievalQueryServiceImpl service = RetrievalQueryServiceTestFactory.create(
                repository, embedding, knowledgeAcl, rerankPort,
                RuntimeConfigTestUnits.defaults(), new SimpleMeterRegistry());

        RetrievalTopNResult result = service.query(new RetrievalTopNQuery(
                "query", List.of(), 5, List.of("missing"), List.of(),
                List.of(), List.of(), null, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.windowFacets()).containsOnlyKeys("assetTypes", "hitTypes");
        assertThat(result.insight().pipeline().fusedRetained()).isZero();
        verify(embedding, never()).embedQuery(any());
        verify(repository, never()).textSearch(any(), anyList(), anyInt(), any());
        verify(repository, never()).vectorSearch(anyList(), anyInt(), anyFloat(), any());
        verify(knowledgeAcl, never()).findActiveIndexGenerations(anyCollection());
        verify(rerankPort, never()).rerank(any(), anyList(), anyInt());
    }

    private RetrievalQueryServiceImpl serviceFor(
            List<SegmentHit> textHits,
            Map<String, Long> activeGenerations,
            SearchRerankPort rerankPort
    ) {
        SegmentRepository repository = mock(SegmentRepository.class);
        QueryEmbeddingService embedding = mock(QueryEmbeddingService.class);
        SearchKnowledgeAcl knowledgeAcl = mock(SearchKnowledgeAcl.class);
        when(knowledgeAcl.resolveVisibleKbIds(List.of("kb-1"))).thenReturn(List.of("kb-1"));
        when(knowledgeAcl.findActiveIndexGenerations(anyCollection())).thenReturn(activeGenerations);
        when(embedding.embedQuery("query")).thenReturn(List.of(0.1F));
        when(repository.textSearch(
                eq("query"), eq(List.of()), eq(32), any(SearchFilter.class)))
                .thenReturn(textHits);
        when(repository.vectorSearch(anyList(), anyInt(), anyFloat(), any(SearchFilter.class)))
                .thenReturn(List.of());
        return RetrievalQueryServiceTestFactory.create(
                repository, embedding, knowledgeAcl, rerankPort,
                RuntimeConfigTestUnits.defaults(), new SimpleMeterRegistry());
    }

    private RetrievalTopNQuery queryFor(List<String> assetIds) {
        return new RetrievalTopNQuery(
                "query", List.of(), 8, List.of("kb-1"), assetIds,
                List.of(), List.of(), null, null);
    }

    private SegmentHit hit(String segmentId, String assetId, int chunkOrder) {
        return SegmentHit.builder()
                .segment(Segment.builder()
                        .segmentId(segmentId)
                        .kbId("kb-1")
                        .assetId(assetId)
                        .indexGeneration(1L)
                        .assetType("PDF")
                        .segmentType(SegmentType.TEXT_CHUNK)
                        .title(segmentId)
                        .contentText("content-" + segmentId)
                        .pageNo(1)
                        .chunkOrder(chunkOrder)
                        .build())
                .rawScore(1D)
                .highlights(Map.of())
                .build();
    }
}
