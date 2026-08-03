package com.anchr.core.conversation.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import java.util.List;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

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

    @Test
    void resolvesDocumentFactsInsideCallerAuthorizedScope() {
        DocumentSummary first = document("asset-1", "kb-2", "first.pdf", 3);
        DocumentSummary duplicate = document("asset-1", "kb-1", "duplicate.pdf", 9);
        DocumentSummary second = document("asset-2", "kb-1", "second.pdf", 5);
        when(knowledgeContentQueryApi.findActiveDocument("kb-2", "asset-2"))
                .thenReturn(Optional.empty());
        when(knowledgeContentQueryApi.findActiveDocument("kb-1", "asset-2"))
                .thenReturn(Optional.of(second));
        when(knowledgeContentQueryApi.searchActiveDocuments("kb-2", "report", 10))
                .thenReturn(List.of(first));
        when(knowledgeContentQueryApi.searchActiveDocuments("kb-1", "report", 10))
                .thenReturn(List.of(duplicate, second));

        assertThat(acl.findActiveDocument(List.of(" kb-2 ", "kb-1"), " asset-2 "))
                .get().satisfies(document -> {
                    assertThat(document.fileName()).isEqualTo("second.pdf");
                    assertThat(document.segmentCount()).isEqualTo(5);
                });
        assertThat(acl.searchActiveDocuments(List.of("kb-2", "kb-1", "kb-2"), " report ", 10))
                .extracting("id", "fileName")
                .containsExactly(
                        Tuple.tuple("asset-1", "first.pdf"),
                        Tuple.tuple("asset-2", "second.pdf"));
    }

    private DocumentSummary document(String id, String kbId, String fileName, int segmentCount) {
        return new DocumentSummary(
                id, kbId, fileName, fileName, "PDF", "application/pdf",
                "docs/" + fileName, "previews/" + fileName, 7L, segmentCount);
    }
}
