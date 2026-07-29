package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.agent.AgentBudget;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentToolException;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeGuardTest {
    private final ConversationKnowledgeAcl knowledgeAcl = mock(ConversationKnowledgeAcl.class);
    private final AgentScopeGuard guard = new AgentScopeGuard(knowledgeAcl);

    @Test
    void shouldResolveUniqueFileNameInsideAuthorizedKnowledgeBases() {
        var asset = asset("asset-1", "kb-1", "经验报告.pdf", "经验报告");
        when(knowledgeAcl.findActiveDocument(List.of("kb-1"), "经验报告.pdf")).thenReturn(Optional.empty());
        when(knowledgeAcl.searchActiveDocuments(List.of("kb-1"), "经验报告.pdf", 50)).thenReturn(List.of(asset));

        ConversationDocumentReference resolved = guard.requireAsset("《经验报告.pdf》", context(List.of()));

        assertThat(resolved.id()).isEqualTo("asset-1");
    }

    @Test
    void shouldReportDocumentNotFoundInsteadOfPermissionWhenReferenceIsInvalid() {
        when(knowledgeAcl.findActiveDocument(List.of("kb-1"), "segment-123")).thenReturn(Optional.empty());
        when(knowledgeAcl.searchActiveDocuments(List.of("kb-1"), "segment-123", 50)).thenReturn(List.of());

        assertThatThrownBy(() -> guard.requireAsset("segment-123", context(List.of())))
                .isInstanceOfSatisfying(AgentToolException.class,
                        error -> assertThat(error.getCode()).isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldKeepPermissionDeniedForAssetOutsideExplicitRequestScope() {
        var asset = asset("asset-outside", "kb-1", "outside.pdf", "outside");
        when(knowledgeAcl.findActiveDocument(List.of("kb-1"), "asset-outside")).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> guard.requireAsset("asset-outside", context(List.of("asset-allowed"))))
                .isInstanceOfSatisfying(AgentToolException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PERMISSION_DENIED"));
    }

    private AgentExecutionContext context(List<String> assets) {
        return new AgentExecutionContext("run", "turn", "session", "user", List.of("kb-1"), assets,
                new AgentBudget(12, 8, System.currentTimeMillis() + 10_000));
    }

    private ConversationDocumentReference asset(String id, String kbId, String fileName, String title) {
        return new ConversationDocumentReference(id, kbId, fileName, title, "PDF", "application/pdf", 7L, 3);
    }
}
