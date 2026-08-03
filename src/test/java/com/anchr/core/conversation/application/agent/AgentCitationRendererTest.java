package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AgentCitationRendererTest {

    @Test
    void shouldRenderMultipleSegmentsFromSameAssetWithHierarchicalIndexes() {
        String answer = "结论一 {{segment:seg-1}}，结论二 {{segment:seg-2}}。";

        AgentCitationRenderResult rendered = AgentCitationRenderer.render(answer, List.of(
                evidence("seg-1", "asset-a"), evidence("seg-2", "asset-a")));

        assertThat(rendered.answer()).isEqualTo("结论一 [1-1]，结论二 [1-2]。");
        assertThat(rendered.references().get("seg-2").label()).isEqualTo("1-2");
    }

    @Test
    void shouldAssignIndexesByAssetFirstOccurrence() {
        String answer = "A {{segment:seg-1}}，B {{segment:seg-2}}，A2 {{segment:seg-3}}。";

        AgentCitationRenderResult rendered = AgentCitationRenderer.render(answer, List.of(
                evidence("seg-3", "asset-a"), evidence("seg-2", "asset-b"),
                evidence("seg-1", "asset-a")));

        assertThat(rendered.answer()).isEqualTo("A [1-1]，B [2-1]，A2 [1-2]。");
    }

    @Test
    void shouldRebuildContinuousIndexesAfterFinalAnswerDropsDraftEvidence() {
        List<ConversationRetrievalCandidate> evidence = List.of(
                evidence("seg-1", "asset-a"),
                evidence("seg-2", "asset-a"),
                evidence("seg-3", "asset-a"));
        Map<String, AgentCitationReference> draftReferences = AgentCitationIndexPlan.build(
                "A {{segment:seg-1}}，B {{segment:seg-2}}，C {{segment:seg-3}}。",
                evidence);

        assertThat(draftReferences.get("seg-3").label()).isEqualTo("1-3");

        AgentCitationRenderResult rendered = AgentCitationRenderer.render(
                "A {{segment:seg-1}}，C {{segment:seg-3}}。",
                List.of(evidence.get(0), evidence.get(2)));

        assertThat(rendered.answer()).isEqualTo("A [1-1]，C [1-2]。");
        assertThat(rendered.references().get("seg-3").label()).isEqualTo("1-2");
    }

    @Test
    void shouldNeverExposeUnknownMarkerOrKnownRawSegmentId() {
        String answer = "不要展示 {{segment:ID}} 或 seg-1，也不要相信模型编号 [9]。";

        AgentCitationRenderResult rendered = AgentCitationRenderer.render(
                answer, List.of(evidence("seg-1", "asset-a")));

        assertThat(rendered.answer()).doesNotContain("segment:ID", "seg-1", "[1-1]");
        assertThat(rendered.answer()).contains("[9]");
        assertThat(rendered.references()).isEmpty();
    }

    @Test
    void shouldNotAppendUnboundEvidenceWhenAnswerHasNoValidMarker() {
        AgentCitationRenderResult rendered = AgentCitationRenderer.render("汇总结论", List.of(
                evidence("seg-1", "asset-a"), evidence("seg-2", "asset-a"),
                evidence("seg-3", "asset-b")));

        assertThat(rendered.answer()).isEqualTo("汇总结论");
        assertThat(rendered.references()).isEmpty();
    }

    @Test
    void shouldNotRewriteBracketedNumbersForDirectChatWithoutEvidence() {
        assertThat(AgentCitationRenderer.render("按你的要求回复：[1]", List.of()).answer())
                .isEqualTo("按你的要求回复：[1]");
    }

    @Test
    void shouldReuseIndexForRepeatedSegmentAndPreserveUnrelatedBracketedNumber() {
        AgentCitationRenderResult rendered = AgentCitationRenderer.render(
                "A {{segment:seg-1}}，B {{segment:seg-1}}，伪造 [9-9]。",
                List.of(evidence("seg-1", "asset-a")));

        assertThat(rendered.answer()).isEqualTo("A [1-1]，B [1-1]，伪造 [9-9]。");
    }

    @Test
    void shouldOnlyConvertMarkersAndPreserveOrdinaryBracketedText() {
        AgentCitationRenderResult rendered = AgentCitationRenderer.render(
                "普通 [1]，数组 arr[1]，区间 [2024-2025]，链接 [1](https://example.com)，结论 {{segment:seg-1}}。",
                List.of(evidence("seg-1", "asset-a")));

        assertThat(rendered.answer()).isEqualTo(
                "普通 [1]，数组 arr[1]，区间 [2024-2025]，链接 [1](https://example.com)，结论 [1-1]。");
    }

    @Test
    void shouldDetectOnlyAuthoredLabelsThatConflictWithRenderedCitations() {
        AgentCitationRenderResult rendered = AgentCitationRenderer.render(
                "结论 {{segment:seg-1}}", List.of(evidence("seg-1", "asset-a")));

        assertThat(AgentCitationRenderer.containsAuthoredVisibleCitation(
                "伪引用 [1-1]，结论 {{segment:seg-1}}", rendered.references())).isTrue();
        assertThat(AgentCitationRenderer.containsAuthoredVisibleCitation(
                "数组 arr[1-1]，链接 [1-1](https://example.com)", rendered.references())).isFalse();
    }

    private ConversationRetrievalCandidate evidence(String segmentId, String assetId) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .build();
    }
}
