package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TraditionalRagRewriteServiceTest {
    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final ConversationGenerationPort model = mock(ConversationGenerationPort.class);
    private final TraditionalRagRewriteService service = new TraditionalRagRewriteService(repository, model,
            new ObjectMapper(), new SimpleMeterRegistry());

    @Test
    void oneCallProducesFullQuestionAndKeywordsWithoutBusinessPromptExamples() {
        String original = "把 AGENT.summaryMaxDocuments 改成10能总结10份？同步Run和异步Task重试何时读取配置？";
        when(repository.findRecentTurns(anyString(), anyInt())).thenReturn(List.of());
        when(model.generate(any(), any())).thenReturn("""
                {"resolvedQuestion":"把 AGENT.summaryMaxDocuments 改成10能总结10份？同步Run和异步Task重试何时读取配置？",
                "keywords":["AGENT.summaryMaxDocuments", "文档数量", "配置读取", "文档数量"],
                "rewriteReason":"提取实体与关系","confidence":0.9}
                """);
        var result = service.rewrite("session", original);
        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(result.getResolvedQuestion()).isEqualTo(original);
        assertThat(result.getRewrittenQuery()).isEqualTo(original);
        assertThat(result.getKeywords()).containsExactly("AGENT summaryMaxDocuments", "文档数量", "配置读取");
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).generate(messages.capture(), any());
        assertThat(messages.getValue().getFirst().content()).doesNotContain("summaryMaxDocuments", "1-3", "同步Run");
        verifyNoMoreInteractions(model);
    }

    @Test
    void passesHistoryToSingleRewriteForFollowUpResolution() {
        var turn = new ConversationTurn(); turn.setQuery("说明组件A的超时设置"); turn.setAnswer("设置位于服务配置中");
        when(repository.findRecentTurns(anyString(), anyInt())).thenReturn(List.of(turn));
        when(model.generate(any(), any())).thenReturn("{\"resolvedQuestion\":\"组件A的超时设置何时生效？\",\"keywords\":[\"组件A\",\"超时\",\"生效时机\"]}");
        var result = service.rewrite("session", "那它什么时候生效？");
        assertThat(result.getResolvedQuestion()).contains("组件A", "何时生效");
        ArgumentCaptor<List<ConversationModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).generate(messages.capture(), any());
        assertThat(messages.getValue()).extracting(ConversationModelMessage::content)
                .containsSubsequence(turn.getQuery(), turn.getAnswer(), "那它什么时候生效？");
    }

    @Test
    void emptyKeywordsKeepResolvedQuestionWithoutRewriteFailure() {
        when(repository.findRecentTurns(anyString(), anyInt())).thenReturn(List.of());
        when(model.generate(any(), any())).thenReturn("{\"resolvedQuestion\":\"组件何时生效？\",\"keywords\":[]}");
        var result = service.rewrite("session", "它何时生效？");
        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(result.getRewrittenQuery()).isEqualTo("组件何时生效？");
        assertThat(result.getKeywords()).isEmpty();
        verify(model).generate(any(), any());
    }

    @Test
    void malformedOrMultipleQueriesFallBackWithoutAdditionalModelCalls() {
        when(repository.findRecentTurns(anyString(), anyInt())).thenReturn(List.of());
        for (String raw : List.of("not json", "{\"resolvedQuestion\":\"完整问题\",\"keywords\":\"a b\"}", "{\"keywords\":[\"a\"]}")) {
            when(model.generate(any(), any())).thenReturn(raw);
            var result = service.rewrite("session", "component.option 是否生效？");
            assertThat(result.isFallbackUsed()).isTrue();
            assertThat(result.getResolvedQuestion()).isEqualTo("component.option 是否生效？");
            assertThat(result.getRewrittenQuery()).isEqualTo("component.option 是否生效？");
            assertThat(result.getKeywords()).isEmpty();
        }
        verify(model, times(3)).generate(any(), any());
    }
}
