package com.anchr.core.search.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchKnowledgeAclTest {

    private final KnowledgeContentQueryApi knowledgeContentQueryApi = mock(KnowledgeContentQueryApi.class);
    private final SearchKnowledgeAcl acl = new SearchKnowledgeAcl(knowledgeContentQueryApi);

    @Test
    void emptyScopeReturnsAllActiveKnowledgeBases() {
        when(knowledgeContentQueryApi.listActiveKnowledgeBases()).thenReturn(activeKbs());

        assertThat(acl.resolveVisibleKbIds(null)).containsExactly("kb-1", "kb-2", "kb-3");
    }

    @Test
    void requestedScopeIsTrimmedDeduplicatedIntersectedAndKeepsRequestOrder() {
        when(knowledgeContentQueryApi.listActiveKnowledgeBases()).thenReturn(activeKbs());

        assertThat(acl.resolveVisibleKbIds(List.of(" kb-3 ", "", "kb-x", "kb-1", "kb-3")))
                .containsExactly("kb-3", "kb-1");
    }

    @Test
    void noActiveKnowledgeBaseFailsClosed() {
        when(knowledgeContentQueryApi.listActiveKnowledgeBases()).thenReturn(List.of());

        assertThat(acl.resolveVisibleKbIds(List.of("kb-1"))).isEmpty();
    }

    private List<KnowledgeBaseSummary> activeKbs() {
        return List.of(
                new KnowledgeBaseSummary("kb-1", "KB 1", "ACTIVE"),
                new KnowledgeBaseSummary("kb-2", "KB 2", "ACTIVE"),
                new KnowledgeBaseSummary("kb-3", "KB 3", "ACTIVE"));
    }
}
