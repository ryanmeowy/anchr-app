package com.smart.vision.core.conversation.application;

import com.smart.vision.core.conversation.application.model.RewriteResult;

/**
 * Query rewrite service for multi-turn conversation retrieval.
 */
public interface QueryRewriteService {

    RewriteResult rewrite(String sessionId, String latestQuery);
}

