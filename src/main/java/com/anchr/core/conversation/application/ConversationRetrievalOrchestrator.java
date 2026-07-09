package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationRetrievalResult;

import java.util.List;

/**
 * Conversation retrieval orchestrator.
 */
public interface ConversationRetrievalOrchestrator {

    ConversationRetrievalResult retrieve(String rewrittenQuery,
                                         Integer limit,
                                         List<String> kbIds,
                                         List<String> preferredModalities,
                                         List<String> assetIdList);
}
