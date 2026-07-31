package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalTopNAssemblerTest {

    private final RetrievalTopNAssembler assembler = new RetrievalTopNAssembler();

    @Test
    void facetsAndInsightShouldPreserveCountsThresholdsAndKnownHitSources() {
        List<RetrievalHit> items = List.of(
                hit("TEXT_CHUNK", "PDF", 0.9D, "VECTOR", "CONTENT"),
                hit("TEXT_CHUNK", "PDF", 0.6D, "TITLE"),
                hit("DOCUMENT_IMAGE", "DOCX", null, "OCR", "TAG", "CAPTION"));

        var facets = assembler.buildWindowFacets(items);
        RetrievalInsight insight = assembler.buildInsight(items, 4, 5, 3, 2, 42L);

        assertThat(facets.get("assetTypes"))
                .extracting(facet -> facet.value() + ":" + facet.count())
                .containsExactly("PDF:2", "DOCX:1");
        assertThat(facets.get("hitTypes"))
                .extracting(facet -> facet.value() + ":" + facet.count())
                .containsExactly("TEXT_CHUNK:2", "DOCUMENT_IMAGE:1");
        assertThat(insight.pipeline()).isEqualTo(new RetrievalInsight.Pipeline(4, 5, 3, 2));
        assertThat(insight.relevanceDistribution())
                .isEqualTo(new RetrievalInsight.RelevanceDistribution(1, 1, 1));
        assertThat(insight.risk()).isEqualTo(new RetrievalInsight.Risk(1));
        assertThat(insight.hitSourceDistribution())
                .isEqualTo(new RetrievalInsight.HitSourceDistribution(1, 1, 1, 1, 1));
        assertThat(insight.latencyMs()).isEqualTo(42L);
    }

    @Test
    void emptyTopNShouldStillExposeBothWindowFacetKeysAndZeroPipeline() {
        assertThat(assembler.buildWindowFacets(List.of()))
                .containsOnlyKeys("assetTypes", "hitTypes")
                .allSatisfy((key, value) -> assertThat(value).isEmpty());
        assertThat(assembler.buildInsight(List.of(), 0, 0, 0, 0, 1L).pipeline())
                .isEqualTo(new RetrievalInsight.Pipeline(0, 0, 0, 0));
    }

    private RetrievalHit hit(
            String segmentType,
            String assetType,
            Double score,
            String... hitSources
    ) {
        return new RetrievalHit(
                segmentType, "title", "content", "TEXT", assetType, "snippet",
                1, score, new RetrievalExplain(List.of(hitSources), null, null, null),
                null, null, null, 1, List.of(),
                segmentType + assetType, "kb-1", "asset-1", null, null, null);
    }
}
