package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.CitationReasonGenerationService;
import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.application.api.model.SearchAnswerRequest;
import com.anchr.core.search.domain.model.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAnswerServiceImplTest {

    @Test
    void answerShouldKeepVisualResultButNeverUseItAsTextEvidence() {
        RetrievalHit visualOnly = hit("visual-1", SegmentType.IMAGE_VISUAL.name(), "asset-1",
                "diagram.png", "diagram.png", null, null, List.of());
        SearchAnswerRequest query = new SearchAnswerRequest("question", null);

        var answer = service().answer(query, List.of(visualOnly));

        assertThat(answer.results()).containsExactly(visualOnly);
        assertThat(answer.answer()).isNull();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void answerShouldUseOcrButNotVisualChunkFromTheSameAsset() {
        RetrievalHit result = hit(null, null, "asset-1", null, null, null, null, List.of(
                chunk("visual-1", SegmentType.IMAGE_VISUAL.name(), "diagram.png", 1.0D,
                        new RetrievalExplain(List.of("VECTOR"), null, null, null), null),
                chunk("ocr-1", SegmentType.IMAGE_OCR_BLOCK.name(), "database architecture", 0.9D,
                        new RetrievalExplain(List.of("OCR"), null, null, null), null)));
        SearchAnswerRequest query = new SearchAnswerRequest("question", null);

        var answer = service().answer(query, List.of(result));

        assertThat(answer.citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.chunks())
                            .extracting("segmentId")
                            .containsExactly("ocr-1");
                    assertThat(citation.chunks().getFirst().why().hitSources())
                            .containsExactly("OCR");
                });
    }

    @Test
    void answer_shouldCarryDocumentChunkOrderIntoCitations() {
        RetrievalHit result = hit("seg-1", null, "asset-1", null, "evidence", 4,
                new RetrievalAnchor(null, 23, List.of(), null, null), List.of());
        SearchAnswerRequest query = new SearchAnswerRequest("question", null);

        var answer = service().answer(query, List.of(result));

        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.assetId()).isEqualTo("asset-1");
            assertThat(citation.chunks()).singleElement().satisfies(chunk -> {
                assertThat(chunk.pageNo()).isEqualTo(4);
                assertThat(chunk.chunkOrder()).isEqualTo(23);
            });
        });
    }

    @Test
    void answer_shouldCreateMultipleCitationsForHitsInTheSameDocument() {
        RetrievalHit result = hit(null, null, "asset-1", null, null, null, null, List.of(
                topChunk("seg-2", 0.8D, 5, 20),
                topChunk("seg-1", 0.9D, 2, 4)));
        result = new RetrievalHit(result.segmentType(), result.title(), result.content(), result.resultType(),
                result.assetType(), result.snippet(), result.pageNo(), result.score(), result.explain(), result.anchor(),
                result.thumbnail(), result.ocrSummary(), result.totalHits(), result.topChunks(), result.segmentId(),
                "kb-1", result.assetId(), "docs/guide.pdf", result.imagePreviewUrl(), result.imagePreviewExpiresAt());
        SearchAnswerRequest query = new SearchAnswerRequest("question", null);

        CitationReasonGenerationService reasonService = request -> Map.of(
                "seg-1", "第一处说明核心机制。",
                "seg-2", "第二处补充应用场景。"
        );
        var answer = new SearchAnswerServiceImpl(reasonService).answer(query, List.of(result));

        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.assetId()).isEqualTo("asset-1");
            assertThat(citation.chunks()).extracting("title")
                    .containsExactly("section seg-1", "section seg-2");
            assertThat(citation.chunks()).extracting(chunk -> chunk.why().reason())
                    .containsExactly("第一处说明核心机制。", "第二处补充应用场景。");
            assertThat(citation.chunks()).extracting("segmentId", "chunkOrder")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("seg-1", 4),
                            org.assertj.core.groups.Tuple.tuple("seg-2", 20));
        });
    }

    private RetrievalTopChunk topChunk(String segmentId, double score, int pageNo, int chunkOrder) {
        return new RetrievalTopChunk(segmentId, null, null, "section " + segmentId,
                "original content " + segmentId, "evidence " + segmentId, null, score, pageNo,
                new RetrievalAnchor(pageNo, chunkOrder, List.of(), null, null),
                null, null, null, null, null);
    }

    private SearchAnswerServiceImpl service() {
        CitationReasonGenerationService reasonService = request -> Map.of();
        return new SearchAnswerServiceImpl(reasonService);
    }

    private RetrievalTopChunk chunk(String id, String type, String snippet, Double score,
                                    RetrievalExplain explain, RetrievalAnchor anchor) {
        return new RetrievalTopChunk(id, null, type, null, null, snippet, explain, score,
                null, anchor, null, null, null, null, null);
    }

    private RetrievalHit hit(String id, String type, String assetId, String title, String snippet,
                             Integer pageNo, RetrievalAnchor anchor, List<RetrievalTopChunk> chunks) {
        return new RetrievalHit(type, title, null, null, null, snippet, pageNo, null,
                null, anchor, null, null, null, chunks, id, null, assetId, null, null, null);
    }
}
