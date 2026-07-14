package com.anchr.core.search.application;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbScopeResolverTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KbScopeResolver resolver = new KbScopeResolver(knowledgeBaseService);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void resolveVisibleKbIds_shouldReturnAllWhenNoFilter() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        when(knowledgeBaseService.listKbs(null, "ACTIVE", null, null, 1, 100)).thenReturn(
                new KnowledgeBaseService.PagedResult<>(kbs(0, 5), 5, 1, 100));

        List<String> result = resolver.resolveVisibleKbIds(null);

        assertThat(result).containsExactly("kb-0", "kb-1", "kb-2", "kb-3", "kb-4");
    }

    @Test
    void resolveVisibleKbIds_shouldFilterByRequestedIds() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        when(knowledgeBaseService.listKbs(null, "ACTIVE", null, null, 1, 100)).thenReturn(
                new KnowledgeBaseService.PagedResult<>(kbs(0, 5), 5, 1, 100));

        List<String> result = resolver.resolveVisibleKbIds(List.of("kb-2", "kb-missing"));

        assertThat(result).containsExactly("kb-2");
    }

    private List<KnowledgeBase> kbs(int start, int count) {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeBase> result = new ArrayList<>();
        for (int i = start; i < start + count; i++) {
            result.add(KnowledgeBase.builder()
                    .id("kb-" + i)
                    .name("KB " + i)
                    .status(KnowledgeBaseStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return result;
    }
}
