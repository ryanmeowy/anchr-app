package com.anchr.core.search.interfaces.rest.assembler;

import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalFacet;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import com.anchr.core.search.application.api.model.RetrievalPageResult;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.application.api.model.SearchAnswerResult;
import com.anchr.core.search.application.model.SearchRewriteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRestAssemblerTest {

    private final SearchRestAssembler assembler = new SearchRestAssembler();

    @Test
    void mapsAllNestedApplicationFieldsBackToExistingRestContract() {
        RetrievalExplain explain = new RetrievalExplain(
                List.of("VECTOR", "OCR"), new RetrievalExplain.MatchedBy(true, false, false, true),
                null, new RetrievalExplain.ImageSignals(true, true, false, false));
        RetrievalAnchor anchor = new RetrievalAnchor(2, 7, List.of(), 1200, 1600);
        RetrievalTopChunk chunk = new RetrievalTopChunk(
                "seg-2", "kb-1", "IMAGE_OCR_BLOCK", "title", "content", "snippet",
                explain, 0.87D, 2, anchor, "source", "https://image", 123L, "thumb", "ocr");
        RetrievalHit hit = new RetrievalHit(
                "IMAGE_OCR_BLOCK", "title", "content", "IMAGE", "PDF", "snippet", 2, 0.87D,
                explain, anchor, "thumb", "ocr", 1, List.of(chunk), "seg-2", "kb-1", "asset-1",
                "source", "https://image", 123L);
        RetrievalInsight insight = new RetrievalInsight(
                new RetrievalInsight.Pipeline(4, 5, 3, 2),
                new RetrievalInsight.RelevanceDistribution(1, 1, 0),
                new RetrievalInsight.Risk(0),
                new RetrievalInsight.HitSourceDistribution(1, 0, 1, 0, 0), 42L);
        RetrievalPageResult page = new RetrievalPageResult(
                List.of(hit), 1, Map.of("assetTypes", List.of(new RetrievalFacet("PDF", 1))), insight);
        SearchRewriteResult rewrite = new SearchRewriteResult();
        rewrite.setRewrittenQuery("rewritten");
        rewrite.setKeywords(List.of("keyword"));
        rewrite.setIntent("LOOKUP");
        rewrite.setIntentCategory("FACT");
        rewrite.setFallbackUsed(false);
        SearchAnswerResult answer = new SearchAnswerResult(
                "answer", List.of(), List.of(hit),
                new SearchAnswerResult.AnswerTrace("STRICT", true, null));

        var dto = assembler.toPageDto(page, rewrite, answer, List.of("next?"));

        assertThat(dto.getRewrittenQuery()).isEqualTo("rewritten");
        assertThat(dto.getRewrittenKeywords()).containsExactly("keyword");
        assertThat(dto.getInsight().getQueryIntent().getIntent()).isEqualTo("LOOKUP");
        assertThat(dto.getInsight().getPipeline().getRerankAdopted()).isEqualTo(2);
        assertThat(dto.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getImagePreviewUrl()).isEqualTo("https://image");
            assertThat(item.getAnchor().getChunkOrder()).isEqualTo(7);
            assertThat(item.getExplain().getHitSources()).containsExactly("VECTOR", "OCR");
            assertThat(item.getTopChunks()).singleElement()
                    .satisfies(top -> assertThat(top.getOcrSummary()).isEqualTo("ocr"));
        });
        assertThat(dto.getAnswer().getResults()).hasSize(1);
        assertThat(dto.getSuggestedQuestions()).containsExactly("next?");
    }
}
