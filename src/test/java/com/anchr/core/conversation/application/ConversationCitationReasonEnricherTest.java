package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCitationReasonEnricherTest {

    private final ConversationRetrievalAcl retrievalAcl = mock(ConversationRetrievalAcl.class);
    private final ConversationCitationReasonEnricher enricher =
            new ConversationCitationReasonEnricher(
                    new ConversationTurnCodec(new ObjectMapper()), retrievalAcl);

    @Test
    void shouldApplyGeneratedReasonToFinalCitation() {
        ConversationCitation citation = citation("seg-1", "语义匹配");
        when(retrievalAcl.generateCitationReasons(any()))
                .thenReturn(Map.of("seg-1", "该段直接说明了回答中的核心结论。"));

        enricher.enrich("问题", "改写问题", "回答 [1-1]", List.of(citation));

        assertThat(citation.getWhy().getReason())
                .isEqualTo("该段直接说明了回答中的核心结论。");
    }

    @Test
    void shouldUseMatchSummaryWhenProviderFails() {
        ConversationCitation citation = citation("seg-1", "内容关键词命中");
        when(retrievalAcl.generateCitationReasons(any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        enricher.enrich("问题", null, "回答 [1-1]", List.of(citation));

        assertThat(citation.getWhy().getReason()).isEqualTo("内容关键词命中");
    }

    @Test
    void shouldUseMatchSummaryWhenProviderReturnsNoResultMap() {
        ConversationCitation citation = citation("seg-1", "OCR关键词命中");
        when(retrievalAcl.generateCitationReasons(any())).thenReturn(null);

        enricher.enrich("问题", null, "回答 [1-1]", List.of(citation));

        assertThat(citation.getWhy().getReason()).isEqualTo("OCR关键词命中");
    }

    @Test
    void shouldLimitEveryAppliedReasonToFiftyCharacters() {
        String longReason = "引用".repeat(30);
        ConversationCitation generated = citation("seg-1", "fallback");
        ConversationCitation fallback = citation("seg-2", longReason);
        when(retrievalAcl.generateCitationReasons(any()))
                .thenReturn(Map.of("seg-1", longReason));

        enricher.enrich("问题", null, "回答 [1-1] [1-2]", List.of(generated, fallback));

        assertThat(generated.getWhy().getReason()).hasSize(50);
        assertThat(fallback.getWhy().getReason()).hasSize(50);
    }

    @Test
    void shouldSkipProviderWhenThereAreNoFinalCitations() {
        enricher.enrich("问题", null, "无引用回答", List.of());

        verify(retrievalAcl, never()).generateCitationReasons(any());
    }

    private ConversationCitation citation(String segmentId, String matchSummary) {
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId(segmentId);
        citation.setAssetId("asset-1");
        citation.setAssetCitationIndex(1);
        citation.setSegmentCitationIndex(1);
        citation.setContent("证据正文");
        citation.setWhy(ConversationCitation.CitationWhy.builder()
                .matchSummary(matchSummary)
                .build());
        return citation;
    }
}
