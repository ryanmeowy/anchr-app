package com.smart.vision.core.conversation.domain.port;

/**
 * Domain port for conversation query rewrite generation.
 */
public interface ConversationRewritePort {

    /**
     * Generate rewrite result text by prompt.
     */
    String generateText(String prompt);
}

