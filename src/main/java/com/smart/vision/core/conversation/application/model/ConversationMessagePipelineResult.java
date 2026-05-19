package com.smart.vision.core.conversation.application.model;

import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import com.smart.vision.core.conversation.interfaces.rest.dto.ResultCardDTO;

import java.util.List;

public record ConversationMessagePipelineResult(RewriteResult rewriteResult,
                                                ConversationRetrievalResult retrievalResult,
                                                List<ResultCardDTO> resultCards,
                                                List<ConversationCitation> answerCitations,
                                                AnswerGenerationResult answerGenerationResult) {
}
