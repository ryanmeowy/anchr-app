package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/**
 * Generates grounded answer from retrieval evidence.
 */
public interface AnswerGenerationService {

    AnswerGenerationResult generate(String userQuery,
                                    String rewrittenQuery,
                                    AnswerMode answerMode,
                                    List<ConversationRetrievalCandidate> topCandidates,
                                    List<ConversationCitation> citations);

    default AnswerGenerationResult generateStream(String userQuery,
                                                  String rewrittenQuery,
                                                  AnswerMode answerMode,
                                                  List<ConversationRetrievalCandidate> topCandidates,
                                                  List<ConversationCitation> citations,
                                                  ConversationProgressListener progress) {
        AnswerGenerationResult result = generate(
                userQuery, rewrittenQuery, answerMode, topCandidates, citations);
        if (progress != null && result.getAnswerText() != null) {
            progress.onAnswerDelta(result.getAnswerText());
        }
        return result;
    }
}
