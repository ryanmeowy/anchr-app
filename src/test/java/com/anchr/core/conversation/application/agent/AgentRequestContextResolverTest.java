package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationKnowledgeBaseReference;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRequestContextResolverTest {
    private final ConversationKnowledgeAcl knowledgeAcl = mock(ConversationKnowledgeAcl.class);
    private final AgentRequestContextResolver resolver = new AgentRequestContextResolver(knowledgeAcl);

    @Test
    void shouldResolveSelectedAssetsFromActiveAuthorizedKnowledgeBases() {
        var kb = new ConversationKnowledgeBaseReference("kb-1", "论文库");
        var asset = document("asset-1", "kb-1", "RAG 总结.pdf", "RAG 总结");
        when(knowledgeAcl.resolveVisibleKnowledgeBases(List.of("kb-1"))).thenReturn(List.of(kb));
        when(knowledgeAcl.findActiveDocument(List.of("kb-1"), "asset-1")).thenReturn(Optional.of(asset));

        AgentRequestContext context = resolver.resolve(run(List.of("kb-1"), List.of("asset-1")));

        assertThat(context.scopeLocked()).isTrue();
        assertThat(context.selectionMode()).isEqualTo("ASSET");
        assertThat(context.knowledgeBaseCount()).isEqualTo(1);
        assertThat(context.assetCount()).isEqualTo(1);
        assertThat(context.selectedKnowledgeBases()).singleElement()
                .satisfies(value -> {
                    assertThat(value.kbId()).isEqualTo("kb-1");
                    assertThat(value.name()).isEqualTo("论文库");
                });
        assertThat(context.selectedAssets()).singleElement()
                .satisfies(value -> {
                    assertThat(value.assetId()).isEqualTo("asset-1");
                    assertThat(value.kbId()).isEqualTo("kb-1");
                    assertThat(value.fileName()).isEqualTo("RAG 总结.pdf");
                    assertThat(value.contentType()).isEqualTo("application/pdf");
                });
    }

    @Test
    void shouldNotExposeAssetsOutsideResolvedKnowledgeBaseScope() {
        when(knowledgeAcl.resolveVisibleKnowledgeBases(List.of("kb-1"))).thenReturn(List.of());

        AgentRequestContext context = resolver.resolve(run(List.of("kb-1"), List.of("asset-outside")));

        assertThat(context.assetCount()).isZero();
        assertThat(context.selectedAssets()).isEmpty();
    }

    private AgentRunRequest run(List<String> kbIds, List<String> assetIds) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("这份文档的核心思想");
        request.setKbIds(kbIds);
        request.setAssetIdList(assetIds);
        return new AgentRunRequest("run", "turn", "session", "single_user", request);
    }

    private ConversationDocumentReference document(String id, String kbId, String fileName, String title) {
        return new ConversationDocumentReference(
                id, kbId, fileName, title, "PDF", "application/pdf", 7L, 3);
    }
}
