package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.*;
import com.anchr.core.conversation.application.agent.AgentWorkflow;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.config.AgentProperties;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationMessageOrchestratorAgentTest {
    @Test
    void enabledRequest_shouldBypassIntentRouterAndTraditionalPipeline() {
        ConversationIntentRouter router = mock(ConversationIntentRouter.class);
        ChatResponseService chat = mock(ChatResponseService.class);
        ConversationMessagePipeline pipeline = mock(ConversationMessagePipeline.class);
        AgentWorkflow workflow = mock(AgentWorkflow.class);
        AgentProperties properties = new AgentProperties(); properties.setEnabled(true);
        when(workflow.execute(any(), any())).thenReturn(new ConversationExecutionResult(null, false, null,
                "agent answer", AnswerStatus.ANSWERED, null, List.of(), List.of(), null,
                "run-1", "general-agent-v1", ConversationExecutionMode.AGENT, null));
        var orchestrator = new ConversationMessageOrchestrator(router, chat, pipeline,
                new SimpleMeterRegistry(), properties, workflow);
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("你好"); request.setAgentEnabled(true);

        var result = orchestrator.execute("session", "turn-1", "run-1", request, ConversationProgressListener.NOOP);

        assertThat(result.executionMode()).isEqualTo(ConversationExecutionMode.AGENT);
        verifyNoInteractions(router, chat, pipeline);
    }

    @Test
    void disabledRequest_shouldKeepTraditionalIntentRouting() {
        ConversationIntentRouter router = mock(ConversationIntentRouter.class);
        when(router.route("session", "你好")).thenReturn(new ConversationIntentResult(
                ConversationIntentType.CHAT, 1, "rule", ConversationIntentSource.RULE, false));
        ChatResponseService chat = mock(ChatResponseService.class);
        when(chat.generate("session", "你好")).thenReturn(new ChatResponseResult("你好", AnswerStatus.ANSWERED, null));
        ConversationMessagePipeline pipeline = mock(ConversationMessagePipeline.class);
        AgentWorkflow workflow = mock(AgentWorkflow.class);
        AgentProperties properties = new AgentProperties(); properties.setEnabled(true);
        var orchestrator = new ConversationMessageOrchestrator(router, chat, pipeline,
                new SimpleMeterRegistry(), properties, workflow);
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("你好"); request.setAgentEnabled(false);

        var result = orchestrator.execute("session", "turn-1", "run-1", request, ConversationProgressListener.NOOP);

        assertThat(result.executionMode()).isEqualTo(ConversationExecutionMode.TRADITIONAL);
        verifyNoInteractions(workflow, pipeline);
    }
}
