package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRequestContextResolverTest {
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final AgentRequestContextResolver resolver = new AgentRequestContextResolver(knowledgeBases, assets);

    @Test
    void shouldResolveSelectedAssetsFromActiveAuthorizedKnowledgeBases() {
        KnowledgeBase kb = KnowledgeBase.builder().id("kb-1").name("论文库").build();
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("RAG 总结.pdf")
                .title("RAG 总结")
                .mimeType("application/pdf")
                .build();
        when(knowledgeBases.listActiveByIds(List.of("kb-1"))).thenReturn(List.of(kb));
        when(assets.findActiveById("kb-1", "asset-1")).thenReturn(Optional.of(asset));

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
        when(knowledgeBases.listActiveByIds(List.of("kb-1"))).thenReturn(List.of());

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
}
