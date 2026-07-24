package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.CitationReasonGenerationService;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAnswerServiceImplTest {

    @Test
    void answerShouldKeepVisualResultButNeverUseItAsTextEvidence() {
        SearchResultDTO visualOnly = SearchResultDTO.builder()
                .segmentId("visual-1")
                .segmentType(SegmentType.IMAGE_VISUAL.name())
                .assetId("asset-1")
                .title("diagram.png")
                .snippet("diagram.png")
                .build();
        SearchQueryDTO query = new SearchQueryDTO();
        query.setQuery("question");

        var answer = service().answer(query, List.of(visualOnly));

        assertThat(answer.getResults()).containsExactly(visualOnly);
        assertThat(answer.getAnswer()).isNull();
        assertThat(answer.getCitations()).isEmpty();
    }

    @Test
    void answerShouldUseOcrButNotVisualChunkFromTheSameAsset() {
        SearchResultDTO result = SearchResultDTO.builder()
                .assetId("asset-1")
                .topChunks(List.of(
                        SearchResultDTO.TopChunk.builder()
                                .segmentId("visual-1")
                                .segmentType(SegmentType.IMAGE_VISUAL.name())
                                .snippet("diagram.png")
                                .explain(SearchExplainDTO.builder()
                                        .hitSources(List.of("VECTOR"))
                                        .build())
                                .score(1.0D)
                                .build(),
                        SearchResultDTO.TopChunk.builder()
                                .segmentId("ocr-1")
                                .segmentType(SegmentType.IMAGE_OCR_BLOCK.name())
                                .snippet("database architecture")
                                .explain(SearchExplainDTO.builder()
                                        .hitSources(List.of("OCR"))
                                        .build())
                                .score(0.9D)
                                .build()))
                .build();
        SearchQueryDTO query = new SearchQueryDTO();
        query.setQuery("question");

        var answer = service().answer(query, List.of(result));

        assertThat(answer.getCitations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.getChunks())
                            .extracting("segmentId")
                            .containsExactly("ocr-1");
                    assertThat(citation.getChunks().getFirst()
                            .getWhy().getHitSources())
                            .containsExactly("OCR");
                });
    }

    @Test
    void answer_shouldCarryDocumentChunkOrderIntoCitations() {
        SearchResultDTO result = SearchResultDTO.builder()
                .segmentId("seg-1")
                .assetId("asset-1")
                .pageNo(4)
                .snippet("evidence")
                .anchor(SearchResultDTO.Anchor.builder().chunkOrder(23).build())
                .build();
        SearchQueryDTO query = new SearchQueryDTO();
        query.setQuery("question");

        var answer = service().answer(query, List.of(result));

        assertThat(answer.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getAssetId()).isEqualTo("asset-1");
            assertThat(citation.getChunks()).singleElement().satisfies(chunk -> {
                assertThat(chunk.getPageNo()).isEqualTo(4);
                assertThat(chunk.getChunkOrder()).isEqualTo(23);
            });
        });
    }

    @Test
    void answer_shouldCreateMultipleCitationsForHitsInTheSameDocument() {
        SearchResultDTO result = SearchResultDTO.builder()
                .assetId("asset-1")
                .kbId("kb-1")
                .sourceRef("docs/guide.pdf")
                .topChunks(List.of(
                        topChunk("seg-2", 0.8D, 5, 20),
                        topChunk("seg-1", 0.9D, 2, 4)))
                .build();
        SearchQueryDTO query = new SearchQueryDTO();
        query.setQuery("question");

        CitationReasonGenerationService reasonService = request -> Map.of(
                "seg-1", "第一处说明核心机制。",
                "seg-2", "第二处补充应用场景。"
        );
        var answer = new SearchAnswerServiceImpl(null, reasonService).answer(query, List.of(result));

        assertThat(answer.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getAssetId()).isEqualTo("asset-1");
            assertThat(citation.getChunks()).extracting("title")
                    .containsExactly("section seg-1", "section seg-2");
            assertThat(citation.getChunks()).extracting(chunk -> chunk.getWhy().getReason())
                    .containsExactly("第一处说明核心机制。", "第二处补充应用场景。");
            assertThat(citation.getChunks()).extracting("segmentId", "chunkOrder")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("seg-1", 4),
                            org.assertj.core.groups.Tuple.tuple("seg-2", 20));
        });
    }

    private SearchResultDTO.TopChunk topChunk(String segmentId, double score, int pageNo, int chunkOrder) {
        return SearchResultDTO.TopChunk.builder()
                .segmentId(segmentId)
                .title("section " + segmentId)
                .content("original content " + segmentId)
                .snippet("evidence " + segmentId)
                .score(score)
                .pageNo(pageNo)
                .anchor(SearchResultDTO.Anchor.builder().pageNo(pageNo).chunkOrder(chunkOrder).build())
                .build();
    }

    private SearchAnswerServiceImpl service() {
        CitationReasonGenerationService reasonService = request -> Map.of();
        return new SearchAnswerServiceImpl(null, reasonService);
    }
}
