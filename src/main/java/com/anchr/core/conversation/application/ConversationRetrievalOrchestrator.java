package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationRetrievalResult;

import java.util.List;

/**
 * Conversation retrieval orchestrator.
 */
public interface ConversationRetrievalOrchestrator {

    ConversationRetrievalResult retrieve(String rewrittenQuery,
                                         Integer topK,
                                         Integer limit,
                                         String strategy,
                                         List<String> preferredModalities);
}

