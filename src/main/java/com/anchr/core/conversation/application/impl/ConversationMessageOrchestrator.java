package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.ChatResponseService;
import com.anchr.core.conversation.application.ConversationIntentRouter;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ChatResponseResult;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationMessageOrchestrator {

    private final ConversationIntentRouter intentRouter;
    private final ChatResponseService chatResponseService;
    private final ConversationMessagePipeline ragPipeline;
    private final MeterRegistry meterRegistry;

    public ConversationExecutionResult execute(String sessionId,
                                               ConversationMessageRequestDTO request,
                                               ConversationProgressListener listener) {
        ConversationProgressListener progress = listener == null ? ConversationProgressListener.NOOP : listener;
        ConversationIntentResult intent = intentRouter.route(sessionId, request.getQuery().trim());
        progress.onRoutingCompleted(intent);
        return switch (intent.type()) {
            case CHAT -> executeChat(sessionId, request, intent, progress);
            case OTHER -> executeOther(intent);
            case KB_QUERY -> executeRag(sessionId, request, intent, progress);
        };
    }

    private ConversationExecutionResult executeChat(String sessionId,
                                                    ConversationMessageRequestDTO request,
                                                    ConversationIntentResult intent,
                                                    ConversationProgressListener progress) {
        progress.onStageStarted("chat_generation");
        meterRegistry.counter("conversation.retrieval.skipped.count", "type", "CHAT").increment();
        ChatResponseResult chat = chatResponseService.generate(sessionId, request.getQuery().trim());
        return new ConversationExecutionResult(intent, false, null, chat.answer(), chat.answerStatus(),
                chat.fallbackReason(), List.of(), List.of(), null);
    }

    private ConversationExecutionResult executeOther(ConversationIntentResult intent) {
        meterRegistry.counter("conversation.retrieval.skipped.count", "type", "OTHER").increment();
        return new ConversationExecutionResult(intent, false, null,
                "我目前主要用于查询、总结和理解知识库中的内容。请补充你想查询的文档或具体问题。", AnswerStatus.ANSWERED,
                null, List.of(), List.of(), null);
    }

    private ConversationExecutionResult executeRag(String sessionId,
                                                   ConversationMessageRequestDTO request,
                                                   ConversationIntentResult intent,
                                                   ConversationProgressListener progress) {
        progress.onStageStarted("retrieval");
        ConversationMessagePipelineResult result = ragPipeline.execute(sessionId, request);
        return new ConversationExecutionResult(intent, true, result.rewriteResult().getRewrittenQuery(),
                result.answerGenerationResult().getAnswerText(), AnswerStatus.from(result.answerGenerationResult()),
                result.answerGenerationResult().getFallbackReason(), result.answerCitations(), result.resultCards(),
                result);
    }
}
