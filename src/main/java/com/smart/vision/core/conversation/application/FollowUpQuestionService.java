package com.smart.vision.core.conversation.application;

import com.smart.vision.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/**
 * Service for generating evidence-related follow-up questions.
 */
public interface FollowUpQuestionService {

    List<String> generate(String userQuery, String rewrittenQuery, List<ConversationCitation> citations);
}
