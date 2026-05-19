package com.smart.vision.core.conversation.application;

import com.smart.vision.core.conversation.application.model.AnswerGenerationResult;
import com.smart.vision.core.conversation.application.model.ConversationRetrievalCandidate;
import com.smart.vision.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/**
 * Generates grounded answer from retrieval evidence.
 */
public interface AnswerGenerationService {

    AnswerGenerationResult generate(String userQuery,
                                    String rewrittenQuery,
                                    List<ConversationRetrievalCandidate> topCandidates,
                                    List<ConversationCitation> citations);
}
