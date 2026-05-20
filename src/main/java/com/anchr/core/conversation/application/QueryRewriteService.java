package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.RewriteResult;

/**
 * Query rewrite service for multi-turn conversation retrieval.
 */
public interface QueryRewriteService {

    RewriteResult rewrite(String sessionId, String latestQuery);
}

