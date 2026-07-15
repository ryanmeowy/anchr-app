package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationIntentResult;

public interface ConversationProgressListener {

    ConversationProgressListener NOOP = new ConversationProgressListener() {
    };

    default void onRoutingCompleted(ConversationIntentResult intent) {
    }

    default void onStageStarted(String stage) {
    }
}
