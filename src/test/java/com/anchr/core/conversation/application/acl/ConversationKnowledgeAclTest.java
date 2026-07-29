package com.anchr.core.conversation.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationKnowledgeAclTest {

    private final KnowledgeContentQueryApi knowledgeContentQueryApi = mock(KnowledgeContentQueryApi.class);
    private final ConversationKnowledgeAcl acl = new ConversationKnowledgeAcl(knowledgeContentQueryApi);

    @Test
    void keepsConversationScopeRulesLocalToConversation() {
        when(knowledgeContentQueryApi.listActiveKnowledgeBases()).thenReturn(List.of(
                new KnowledgeBaseSummary("kb-1", "KB 1", "ACTIVE"),
                new KnowledgeBaseSummary("kb-2", "KB 2", "ACTIVE")));

        assertThat(acl.resolveVisibleKbIds(List.of(" kb-2 ", "kb-x", "kb-1", "kb-2", "")))
                .containsExactly("kb-2", "kb-1");
        assertThat(acl.resolveVisibleKbIds(List.of())).containsExactly("kb-1", "kb-2");
    }
}
