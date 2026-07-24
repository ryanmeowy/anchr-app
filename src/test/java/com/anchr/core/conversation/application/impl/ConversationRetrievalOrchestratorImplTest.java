package com.anchr.core.conversation.application.impl;

import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConversationRetrievalOrchestratorImplTest {

    @Mock
    private UnifiedSearchService unifiedSearchService;

    private ConversationRetrievalOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ConversationRetrievalOrchestratorImpl(
                unifiedSearchService,
                new SimpleMeterRegistry()
        );
        org.mockito.Mockito.lenient().when(unifiedSearchService.search(any(SearchQueryDTO.class))).thenReturn(List.of(
                result("seg_text", "TEXT_CHUNK"),
                result("seg_image", "IMAGE_CAPTION")
        ));
    }

    @Test
    void retrieve_shouldKeepAllModalitiesWhenUserDoesNotRestrictThem() {
        var result = orchestrator.retrieve("RAG 架构", 10, List.of("kb_1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getSegmentId())
                .containsExactly("seg_text", "seg_image");
        assertThat(result.getTopCandidates().getFirst().getContent())
                .isEqualTo("original content seg_text");
        ArgumentCaptor<SearchQueryDTO> queryCaptor = ArgumentCaptor.forClass(SearchQueryDTO.class);
        verify(unifiedSearchService).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getHitTypes()).isEmpty();
    }

    @Test
    void retrieve_shouldPushUserTextRestrictionIntoSearchQuery() {
        var result = orchestrator.retrieve("RAG 定义", 10, List.of("kb_1"), List.of("TEXT"), null);

        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getSegmentId())
                .containsExactly("seg_text", "seg_image");
        ArgumentCaptor<SearchQueryDTO> queryCaptor = ArgumentCaptor.forClass(SearchQueryDTO.class);
        verify(unifiedSearchService).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getHitTypes()).containsExactly("TEXT_CHUNK");
    }

    @Test
    void retrieve_shouldExpandMultipleHitsFromTheSameDocumentIntoSegmentCandidates() {
        when(unifiedSearchService.search(any(SearchQueryDTO.class))).thenReturn(List.of(
                SearchResultDTO.builder()
                        .assetId("asset-1")
                        .sourceRef("docs/guide.pdf")
                        .topChunks(List.of(
                                topChunk("seg-later", 0.8D, 5, 20),
                                topChunk("seg-earlier", 0.9D, 2, 4)))
                        .build()));

        var result = orchestrator.retrieve("RAG", 10, List.of("kb-1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates())
                .extracting("segmentId", "assetId", "pageNo")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("seg-earlier", "asset-1", 2),
                        org.assertj.core.groups.Tuple.tuple("seg-later", "asset-1", 5));
        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getAnchor().getChunkOrder())
                .containsExactly(4, 20);
        assertThat(result.getTopCandidates()).extracting("title")
                .containsExactly("section seg-earlier", "section seg-later");
    }

    @Test
    void retrieveShouldKeepEachTopChunksOwnHitExplanation() {
        when(unifiedSearchService.search(any(SearchQueryDTO.class)))
                .thenReturn(List.of(SearchResultDTO.builder()
                        .assetId("asset-1")
                        .explain(SearchExplainDTO.builder()
                                .hitSources(List.of("VECTOR"))
                                .build())
                        .topChunks(List.of(SearchResultDTO.TopChunk.builder()
                                .segmentId("ocr-1")
                                .segmentType("IMAGE_OCR_BLOCK")
                                .explain(SearchExplainDTO.builder()
                                        .hitSources(List.of("OCR"))
                                        .build())
                                .score(0.8D)
                                .build()))
                        .build()));

        var result = orchestrator.retrieve(
                "diagram", 10, List.of("kb-1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates()).singleElement()
                .satisfies(candidate -> assertThat(
                        candidate.getExplain().getHitSources())
                        .containsExactly("OCR"));
    }

    private SearchResultDTO result(String segmentId, String segmentType) {
        return SearchResultDTO.builder()
                .segmentId(segmentId)
                .segmentType(segmentType)
                .content("original content " + segmentId)
                .snippet("evidence " + segmentId)
                .score(0.9D)
                .build();
    }

    private SearchResultDTO.TopChunk topChunk(String segmentId, double score, int pageNo, int chunkOrder) {
        return SearchResultDTO.TopChunk.builder()
                .segmentId(segmentId)
                .title("section " + segmentId)
                .segmentType("TEXT_CHUNK")
                .content("original content " + segmentId)
                .snippet("evidence " + segmentId)
                .score(score)
                .pageNo(pageNo)
                .anchor(SearchResultDTO.Anchor.builder().pageNo(pageNo).chunkOrder(chunkOrder).build())
                .sourceRef("docs/guide.pdf")
                .build();
    }
}
