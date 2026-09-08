package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationRetrievalResult;

import java.util.List;

/**
 * Conversation retrieval orchestrator.
 */
public interface ConversationRetrievalOrchestrator {

    default ConversationRetrievalResult retrieve(String query, List<String> keywords, Integer limit,
                                                 List<String> kbIds, List<String> preferredModalities,
                                                 List<String> assetIdList) {
        if (keywords != null && !keywords.isEmpty()) {
            throw new UnsupportedOperationException("Keyword retrieval is not implemented");
        }
        return retrieve(query, limit, kbIds, preferredModalities, assetIdList);
    }

    ConversationRetrievalResult retrieve(String rewrittenQuery,
                                         Integer limit,
                                         List<String> kbIds,
                                         List<String> preferredModalities,
                                         List<String> assetIdList);
}
