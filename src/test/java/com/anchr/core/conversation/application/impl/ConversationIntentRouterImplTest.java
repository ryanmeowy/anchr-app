package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.model.ConversationIntentSource;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.GenerationOptions;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationIntentRouterImplTest {

    @Mock
    private ConversationRepository repository;
    @Mock
    private ConversationGenerationPort generationPort;

    private ConversationIntentRouterImpl router;

    @BeforeEach
    void setUp() {
        router = new ConversationIntentRouterImpl(repository, generationPort,
                new ObjectMapper(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(router, "enabled", true);
        ReflectionTestUtils.setField(router, "contextTurnLimit", 5);
        ReflectionTestUtils.setField(router, "timeout", Duration.ofSeconds(5));
    }

    @Test
    void shouldRouteExactGreetingByRule() {
        var result = router.route("session", " 你好！ ");

        assertThat(result.type()).isEqualTo(ConversationIntentType.CHAT);
        assertThat(result.source()).isEqualTo(ConversationIntentSource.RULE);
        verify(generationPort, never()).generate(any(), any());
    }

    @Test
    void shouldResolveContextualSelectionWithoutDependingOnItsSurfaceForm() {
        ConversationTurn turn = new ConversationTurn();
        turn.setQuery("你可以做哪些事");
        turn.setAnswer("1. 回答知识库问题\n2. 总结文档");
        turn.setIntentType("CHAT");
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of(turn));
        when(generationPort.generate(any(), any())).thenReturn(
                "{\"type\":\"OTHER\",\"confidence\":0.96,\"reason\":\"缺少具体知识问题\"}");

        var result = router.route("session", "1");

        assertThat(result.type()).isEqualTo(ConversationIntentType.OTHER);
        assertThat(result.source()).isEqualTo(ConversationIntentSource.MODEL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(generationPort).generate(messages.capture(), any());
        assertThat(messages.getValue()).extracting(ConversationModelMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.getValue().get(0).content()).contains("上下文意图解析器");
        assertThat(messages.getValue().subList(1, 4)).extracting(ConversationModelMessage::content)
                .containsExactly("你可以做哪些事", "1. 回答知识库问题\n2. 总结文档", "1");
    }

    @Test
    void shouldUseModelForGreetingCombinedWithKnowledgeQuestion() {
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any())).thenReturn(
                "{\"type\":\"KB_QUERY\",\"confidence\":0.97,\"reason\":\"需要查询合同\"}");

        var result = router.route("session", "你好，请总结合同内容");

        assertThat(result.type()).isEqualTo(ConversationIntentType.KB_QUERY);
        assertThat(result.source()).isEqualTo(ConversationIntentSource.MODEL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<GenerationOptions> options = ArgumentCaptor.forClass(GenerationOptions.class);
        verify(generationPort).generate(messages.capture(), options.capture());
        assertThat(messages.getValue()).extracting(ConversationModelMessage::role)
                .containsExactly("system", "user");
        assertThat(options.getValue().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.getValue().maxTokens()).isEqualTo(300);
    }

    @Test
    void shouldKeepModelClassificationWhenSelfReportedConfidenceIsLow() {
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any())).thenReturn(
                "```json\n{\"type\":\"CHAT\",\"confidence\":0.5,\"reason\":\"不确定\"}\n```");

        var result = router.route("session", "今天怎么样");

        assertThat(result.type()).isEqualTo(ConversationIntentType.CHAT);
        assertThat(result.confidence()).isEqualTo(0.5D);
        assertThat(result.source()).isEqualTo(ConversationIntentSource.MODEL);
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void shouldFallbackToClarificationForInvalidModelResponse() {
        when(repository.findRecentTurns("session", 5)).thenReturn(List.of());
        when(generationPort.generate(any(), any())).thenReturn("not-json");

        var result = router.route("session", "帮我看看");

        assertThat(result.type()).isEqualTo(ConversationIntentType.OTHER);
        assertThat(result.source()).isEqualTo(ConversationIntentSource.FALLBACK);
    }
}
