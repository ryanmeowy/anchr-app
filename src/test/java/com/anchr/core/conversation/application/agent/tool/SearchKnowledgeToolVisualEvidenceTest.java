package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKnowledgeToolVisualEvidenceTest {

    @Test
    void visualHitShouldNotBeReturnedAsCitableEvidence() {
        RewriteResult rewrite = new RewriteResult();
        rewrite.setRewrittenQuery("rewritten");
        rewrite.setKeywords(List.of("ocr", "证据"));
        ConversationRetrievalResult retrieval = new ConversationRetrievalResult();
        retrieval.setTopCandidates(List.of(
                candidate("visual-1", SegmentType.IMAGE_VISUAL),
                candidate("ocr-1", SegmentType.IMAGE_OCR_BLOCK)));
        List<List<String>> capturedKeywords = new ArrayList<>();
        SearchKnowledgeTool tool = new SearchKnowledgeTool(
                (sessionId, query) -> rewrite,
                new ConversationRetrievalOrchestrator() {
                    @Override
                    public ConversationRetrievalResult retrieve(String query, List<String> keywords, Integer limit,
                                                               List<String> kbIds, List<String> modalities,
                                                               List<String> assetIds) {
                        capturedKeywords.add(keywords);
                        return retrieval;
                    }

                    @Override
                    public ConversationRetrievalResult retrieve(String query, Integer limit, List<String> kbIds,
                                                               List<String> modalities, List<String> assetIds) {
                        return retrieval;
                    }
                },
                new ObjectMapper());
        AgentExecutionContext context = new AgentExecutionContext(
                "run-1", "turn-1", "session-1", "user-1",
                List.of("kb-1"), List.of(), null);

        var result = tool.execute(
                new SearchKnowledgeTool.Input(
                        "question", List.of(), 8, List.of("MIXED")),
                context);

        assertThat(result.evidence())
                .extracting(ConversationRetrievalCandidate::getSegmentId)
                .containsExactly("ocr-1");
        assertThat(result.content()).contains("ocr-1");
        assertThat(result.content()).doesNotContain("visual-1");
        assertThat(capturedKeywords).containsExactly(List.of("ocr", "证据"));
    }

    private ConversationRetrievalCandidate candidate(
            String id,
            SegmentType type
    ) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(id)
                .assetId("asset-1")
                .segmentType(type.name())
                .content(type == SegmentType.IMAGE_VISUAL
                        ? "" : "recognized text")
                .build();
    }
}
