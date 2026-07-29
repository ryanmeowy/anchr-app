package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindDocumentsToolVisualEvidenceTest {

    @Test
    void visualHitMayFindTheDocumentButMustNotBecomeEvidence() {
        var asset = new ConversationDocumentReference(
                "asset-1", "kb-1", "diagram.png", null,
                "IMAGE", "image/png", 7L, 1);
        ConversationKnowledgeAcl knowledgeAcl = mock(ConversationKnowledgeAcl.class);
        when(knowledgeAcl.searchActiveDocuments(List.of("kb-1"), "diagram", 5)).thenReturn(List.of());
        when(knowledgeAcl.findActiveDocument(List.of("kb-1"), "asset-1")).thenReturn(Optional.of(asset));
        ConversationRetrievalResult retrieval = new ConversationRetrievalResult();
        retrieval.setTopCandidates(List.of(
                ConversationRetrievalCandidate.builder()
                        .segmentId("visual-1")
                        .kbId("kb-1")
                        .assetId("asset-1")
                        .segmentType(SegmentType.IMAGE_VISUAL.name())
                        .score(0.95D)
                        .build()));
        FindDocumentsTool tool = new FindDocumentsTool(
                knowledgeAcl,
                (query, limit, kbIds, modalities, assetIds) -> retrieval,
                new ObjectMapper());
        AgentExecutionContext context = new AgentExecutionContext(
                "run-1", "turn-1", "session-1", "user-1",
                List.of("kb-1"), List.of(), null);

        var result = tool.execute(
                new FindDocumentsTool.Input("diagram", 5),
                context);

        assertThat(result.content()).contains("asset-1");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.traceDetails())
                .containsEntry("documentCount", 1)
                .containsEntry("evidenceCount", 0);
    }
}
