package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.*;
import com.anchr.core.conversation.application.agent.AgentWorkflow;
import com.anchr.core.conversation.application.agent.AgentWorkflowException;
import com.anchr.core.conversation.application.agent.AgentRunFinalizer;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
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
        AgentRunFinalizer finalizer = mock(AgentRunFinalizer.class);
        when(workflow.execute(any(), any())).thenReturn(new ConversationExecutionResult(null, false, null,
                "agent answer", AnswerStatus.ANSWERED, null, List.of(), List.of(), null,
                "run-1", ConversationExecutionMode.AGENT, null));
        var orchestrator = new ConversationMessageOrchestrator(router, chat, pipeline,
                new SimpleMeterRegistry(), RuntimeConfigTestUnits.defaults(),
                workflow, finalizer);
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
        AgentRunFinalizer finalizer = mock(AgentRunFinalizer.class);
        var orchestrator = new ConversationMessageOrchestrator(router, chat, pipeline,
                new SimpleMeterRegistry(), RuntimeConfigTestUnits.defaults(),
                workflow, finalizer);
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("你好"); request.setAgentEnabled(false);

        var result = orchestrator.execute("session", "turn-1", "run-1", request, ConversationProgressListener.NOOP);

        assertThat(result.executionMode()).isEqualTo(ConversationExecutionMode.TRADITIONAL);
        verifyNoInteractions(workflow, pipeline);
    }

    @Test
    void agentFallback_shouldPreserveTraditionalGenerationFailureStatus() {
        ConversationIntentRouter router = mock(ConversationIntentRouter.class);
        when(router.route("session", "RAG 是什么")).thenReturn(new ConversationIntentResult(
                ConversationIntentType.KB_QUERY, 1, "model", ConversationIntentSource.MODEL, false));
        ChatResponseService chat = mock(ChatResponseService.class);
        ConversationMessagePipeline pipeline = mock(ConversationMessagePipeline.class);
        AgentWorkflow workflow = mock(AgentWorkflow.class);
        AgentRunFinalizer finalizer = mock(AgentRunFinalizer.class);
        when(workflow.execute(any(), any())).thenThrow(new AgentWorkflowException("agent failed", null));

        RewriteResult rewrite = new RewriteResult();
        rewrite.setRewrittenQuery("RAG 定义");
        AnswerGenerationResult failure = new AnswerGenerationResult();
        failure.setAnswerText("回答模型未能生成可靠结果，请稍后重试。");
        failure.setGenerationFailed(true);
        failure.setFallbackReason("model_unavailable");
        when(pipeline.execute(
                any(String.class),
                any(ConversationMessageRequestDTO.class),
                any(ConversationProgressListener.class)))
                .thenReturn(new ConversationMessagePipelineResult(
                rewrite, new ConversationRetrievalResult(), List.of(), List.of(), failure));

        var orchestrator = new ConversationMessageOrchestrator(router, chat, pipeline,
                new SimpleMeterRegistry(), RuntimeConfigTestUnits.defaults(),
                workflow, finalizer);
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("RAG 是什么");
        request.setAgentEnabled(true);

        var result = orchestrator.execute(
                "session", "turn-1", "run-1", request, ConversationProgressListener.NOOP);

        assertThat(result.executionMode()).isEqualTo(ConversationExecutionMode.AGENT_FALLBACK);
        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.GENERATION_FAILED);
        assertThat(result.fallbackReason()).isEqualTo("model_unavailable");
        verify(pipeline).execute(
                any(String.class),
                any(ConversationMessageRequestDTO.class),
                any(ConversationProgressListener.class));
    }
}
