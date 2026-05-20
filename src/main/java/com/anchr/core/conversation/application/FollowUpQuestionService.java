package com.anchr.core.conversation.application;

import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/**
 * Service for generating evidence-related follow-up questions.
 */
public interface FollowUpQuestionService {

    List<String> generate(String userQuery, String rewrittenQuery, List<ConversationCitation> citations);
}
