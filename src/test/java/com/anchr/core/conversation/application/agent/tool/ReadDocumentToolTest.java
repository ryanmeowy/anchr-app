package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.agent.AgentBudget;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadDocumentToolTest {

    @Test
    void execute_shouldCarryAssetFileNameIntoCitationEvidence() {
        AgentScopeGuard scopeGuard = mock(AgentScopeGuard.class);
        ConversationRetrievalAcl retrievalAcl = mock(ConversationRetrievalAcl.class);
        ReadDocumentTool tool = new ReadDocumentTool(scopeGuard, retrievalAcl, new ObjectMapper());
        AgentExecutionContext context = new AgentExecutionContext(
                "run-1", "turn-1", "session-1", "user-1",
                List.of("kb-1"), List.of("asset-1"),
                new AgentBudget(12, 8, System.currentTimeMillis() + 10_000));
        var asset = new ConversationDocumentReference(
                "asset-1", "kb-1", "Corrective Retrieval Augmented Generation.pdf",
                "CRAG", "PDF", "application/pdf", 7L, 1);
        var candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg-1").kbId("kb-1").assetId("asset-1")
                .segmentType("TEXT_CHUNK").content("不同置信度对应不同动作")
                .pageNo(3)
                .sourceRef("Corrective Retrieval Augmented Generation.pdf")
                .anchor(ConversationRetrievalCandidate.Anchor.builder()
                        .pageNo(3).chunkOrder(7).build())
                .build();
        when(scopeGuard.requireAsset("asset-1", context)).thenReturn(asset);
        when(retrievalAcl.readDocument(asset, null, null, 21))
                .thenReturn(List.of(candidate));

        var result = tool.execute(new ReadDocumentTool.Input("asset-1", null, 20), context);

        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getSourceRef()).isEqualTo("Corrective Retrieval Augmented Generation.pdf");
            assertThat(evidence.getAssetId()).isEqualTo("asset-1");
        });
    }
}
