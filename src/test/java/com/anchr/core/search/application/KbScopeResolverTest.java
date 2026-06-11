package com.anchr.core.search.application;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbScopeResolverTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final KbScopeResolver resolver = new KbScopeResolver(knowledgeBaseRepository);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void resolveVisibleKbIds_shouldLoadAllActiveKnowledgeBasesByPage() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        when(knowledgeBaseRepository.listActive("ws-a", 100, 0)).thenReturn(kbs(0, 100));
        when(knowledgeBaseRepository.listActive("ws-a", 100, 100)).thenReturn(kbs(100, 1));

        List<String> result = resolver.resolveVisibleKbIds(null);

        assertThat(result).hasSize(101);
        assertThat(result.getFirst()).isEqualTo("kb-0");
        assertThat(result.getLast()).isEqualTo("kb-100");
        verify(knowledgeBaseRepository).listActive("ws-a", 100, 0);
        verify(knowledgeBaseRepository).listActive("ws-a", 100, 100);
    }

    @Test
    void resolveVisibleKbIds_shouldMatchRequestedIdsBeyondFirstPage() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        when(knowledgeBaseRepository.listActive("ws-a", 100, 0)).thenReturn(kbs(0, 100));
        when(knowledgeBaseRepository.listActive("ws-a", 100, 100)).thenReturn(kbs(100, 1));

        List<String> result = resolver.resolveVisibleKbIds(List.of("kb-100", "kb-missing"));

        assertThat(result).containsExactly("kb-100");
    }

    private List<KnowledgeBase> kbs(int start, int count) {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeBase> result = new ArrayList<>();
        for (int i = start; i < start + count; i++) {
            result.add(KnowledgeBase.builder()
                    .id("kb-" + i)
                    .workspaceId("ws-a")
                    .name("KB " + i)
                    .status(KnowledgeBaseStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return result;
    }
}
