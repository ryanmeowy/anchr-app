package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.ChatResponseService;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.conversation.application.ConversationIntentRouter;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.agent.AgentRunRequest;
import com.anchr.core.conversation.application.agent.AgentRunFinalizer;
import com.anchr.core.conversation.application.agent.AgentWorkflow;
import com.anchr.core.conversation.application.agent.AgentWorkflowException;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ChatResponseResult;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.application.model.ConversationExecutionMode;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ConversationMessageOrchestrator {

    private final ConversationIntentRouter intentRouter;
    private final ChatResponseService chatResponseService;
    private final ConversationMessagePipeline ragPipeline;
    private final MeterRegistry meterRegistry;
    private final RuntimeConfigUnit runtimeConfigUnit;
    private final AgentWorkflow agentWorkflow;
    private final AgentRunFinalizer agentRunFinalizer;

    public ConversationMessageOrchestrator(ConversationIntentRouter intentRouter,
                                           ChatResponseService chatResponseService,
                                           ConversationMessagePipeline ragPipeline,
                                           MeterRegistry meterRegistry,
                                           RuntimeConfigUnit runtimeConfigUnit,
                                           AgentWorkflow agentWorkflow,
                                           AgentRunFinalizer agentRunFinalizer) {
        this.intentRouter = intentRouter;
        this.chatResponseService = chatResponseService;
        this.ragPipeline = ragPipeline;
        this.meterRegistry = meterRegistry;
        this.runtimeConfigUnit = runtimeConfigUnit;
        this.agentWorkflow = agentWorkflow;
        this.agentRunFinalizer = agentRunFinalizer;
    }

    public ConversationExecutionResult execute(String sessionId,
                                               ConversationMessageRequestDTO request,
                                               ConversationProgressListener listener) {
        return execute(sessionId, newTurnId(), newRunId(), request, listener);
    }

    public ConversationExecutionResult execute(String sessionId,
                                               String turnId,
                                               String runId,
                                               ConversationMessageRequestDTO request,
                                               ConversationProgressListener listener) {
        ConversationProgressListener progress = listener == null ? ConversationProgressListener.NOOP : listener;
        boolean agentEnabled =
                runtimeConfigUnit.getBoolean("AGENT", "enabled", true);
        boolean fallbackToTraditional = runtimeConfigUnit.getBoolean(
                "AGENT", "fallbackToTraditional", true);
        if (Boolean.TRUE.equals(request.getAgentEnabled()) && agentEnabled) {
            try {
                return agentWorkflow.execute(new AgentRunRequest(runId, turnId, sessionId,
                        "single_user", request), progress);
            } catch (AgentWorkflowException e) {
                if (!fallbackToTraditional) throw e;
                meterRegistry.counter("agent.workflow.fallback.count", "target", "traditional").increment();
                ConversationIntentResult fallbackIntent = intentRouter.route(sessionId, request.getQuery().trim());
                progress.onRoutingCompleted(fallbackIntent);
                ConversationExecutionResult fallback = switch (fallbackIntent.type()) {
                    case CHAT -> executeChat(sessionId, request, fallbackIntent, progress);
                    case OTHER -> executeOther(fallbackIntent);
                    case KB_QUERY -> executeLegacyRag(sessionId, request, fallbackIntent, progress, runId);
                };
                agentRunFinalizer.prepareTraditionalFallback(runId);
                return new ConversationExecutionResult(fallback.intent(), fallback.retrievalExecuted(),
                        fallback.rewrittenQuery(), fallback.answer(), fallback.answerStatus(), fallback.fallbackReason(),
                        fallback.citations(), fallback.resultCards(), fallback.ragResult(), runId,
                        ConversationExecutionMode.AGENT_FALLBACK, null);
            }
        }
        ConversationIntentResult intent = intentRouter.route(sessionId, request.getQuery().trim());
        progress.onRoutingCompleted(intent);
        return switch (intent.type()) {
            case CHAT -> executeChat(sessionId, request, intent, progress);
            case OTHER -> executeOther(intent);
            case KB_QUERY -> executeLegacyRag(sessionId, request, intent, progress, null);
        };
    }

    private ConversationExecutionResult executeChat(String sessionId,
                                                    ConversationMessageRequestDTO request,
                                                    ConversationIntentResult intent,
                                                    ConversationProgressListener progress) {
        progress.onStageStarted("chat_generation");
        meterRegistry.counter("conversation.retrieval.skipped.count", "type", "CHAT").increment();
        ChatResponseResult chat = progress.supportsAnswerStreaming()
                ? chatResponseService.generateStream(sessionId, request.getQuery().trim(), progress)
                : chatResponseService.generate(sessionId, request.getQuery().trim());
        return new ConversationExecutionResult(intent, false, null, chat.answer(), chat.answerStatus(),
                chat.fallbackReason(), List.of(), List.of(), null, null);
    }

    private ConversationExecutionResult executeOther(ConversationIntentResult intent) {
        meterRegistry.counter("conversation.retrieval.skipped.count", "type", "OTHER").increment();
        return new ConversationExecutionResult(intent, false, null,
                "我目前主要用于查询、总结和理解知识库中的内容。请补充你想查询的文档或具体问题。", AnswerStatus.ANSWERED,
                null, List.of(), List.of(), null, null);
    }

    private ConversationExecutionResult executeLegacyRag(String sessionId,
                                                          ConversationMessageRequestDTO request,
                                                          ConversationIntentResult intent,
                                                          ConversationProgressListener progress,
                                                          String agentRunId) {
        progress.onStageStarted("retrieval");
        ConversationMessagePipelineResult result = ragPipeline.execute(sessionId, request, progress);
        return new ConversationExecutionResult(intent, true, result.rewriteResult().getRewrittenQuery(),
                result.answerGenerationResult().getAnswerText(), AnswerStatus.from(result.answerGenerationResult()),
                result.answerGenerationResult().getFallbackReason(), result.answerCitations(), result.resultCards(),
                result, agentRunId);
    }

    private static String newTurnId() {
        return "turn_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "");
    }

}
