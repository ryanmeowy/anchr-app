package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.GenerationOptions;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceImplTest {

    @Mock
    private ConversationRepository repository;
    @Mock
    private ConversationGenerationPort generationPort;

    @Test
    void shouldResolveContextDependentFollowUpUsingAssistantAnswer() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                repository, generationPort, new ObjectMapper(), new SimpleMeterRegistry());
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId("turn_1");
        turn.setQuery("给我两个部署方案");
        turn.setRewrittenQuery("应用部署方案");
        turn.setAnswer("1. 使用 Docker 部署\n2. 使用本地进程部署");
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of(turn));
        when(generationPort.generate(any(), any())).thenReturn("""
                {"rewrittenQuery":"Docker 部署流程","rewriteReason":"用户选择上一轮第1个方案","topicEntities":["Docker"],"confidence":0.95}
                """);

        var result = service.rewrite("session", "1");

        assertThat(result.getRewrittenQuery()).isEqualTo("Docker 部署流程");
        assertThat(result.isFallbackUsed()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<GenerationOptions> options = ArgumentCaptor.forClass(GenerationOptions.class);
        verify(generationPort).generate(messages.capture(), options.capture());
        assertThat(messages.getValue()).extracting(ConversationModelMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.getValue().getFirst().content()).contains("知识库检索 Query 重写器");
        assertThat(messages.getValue().subList(1, 4)).extracting(ConversationModelMessage::content)
                .containsExactly("给我两个部署方案", "1. 使用 Docker 部署\n2. 使用本地进程部署", "1");
        assertThat(options.getValue().temperature()).isZero();
        assertThat(options.getValue().maxTokens()).isEqualTo(300);
    }

    @Test
    void invalidModelJsonShouldKeepOriginalQueryAndMarkFallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                repository, generationPort, new ObjectMapper(), registry);
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any()))
                .thenReturn("{\"rewrittenQuery\":\"broken\",invalid}");

        var result = service.rewrite("session", "原始问题");

        assertThat(result.getRewrittenQuery()).isEqualTo("原始问题");
        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(registry.counter("query.rewrite.fallback.count").count()).isEqualTo(1D);
    }

    @Test
    void missingRewrittenQueryShouldKeepOriginalQueryAndMarkFallback() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                repository, generationPort, new ObjectMapper(), registry);
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any()))
                .thenReturn("{\"rewriteReason\":\"missing required field\",\"confidence\":0.9}");

        var result = service.rewrite("session", "原始问题");

        assertThat(result.getRewrittenQuery()).isEqualTo("原始问题");
        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(registry.counter("query.rewrite.fallback.count").count()).isEqualTo(1D);
    }

}
