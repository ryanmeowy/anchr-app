package com.anchr.core.conversation.application.model;

import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;

import java.util.List;

public record ConversationExecutionResult(ConversationIntentResult intent,
                                          boolean retrievalExecuted,
                                          String rewrittenQuery,
                                          String answer,
                                          AnswerStatus answerStatus,
                                          String fallbackReason,
                                          List<ConversationCitation> citations,
                                          List<ResultCardDTO> resultCards,
                                          ConversationMessagePipelineResult ragResult) {
}
