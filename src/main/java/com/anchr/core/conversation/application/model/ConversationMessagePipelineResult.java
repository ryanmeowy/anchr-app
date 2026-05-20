package com.anchr.core.conversation.application.model;

import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;

import java.util.List;

public record ConversationMessagePipelineResult(RewriteResult rewriteResult,
                                                ConversationRetrievalResult retrievalResult,
                                                List<ResultCardDTO> resultCards,
                                                List<ConversationCitation> answerCitations,
                                                AnswerGenerationResult answerGenerationResult) {
}
