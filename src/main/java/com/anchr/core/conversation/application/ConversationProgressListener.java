package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.AgentProgressEvent;

public interface ConversationProgressListener {

    ConversationProgressListener NOOP = new ConversationProgressListener() {
    };

    default void onRoutingCompleted(ConversationIntentResult intent) {
    }

    default void onStageStarted(String stage) {
    }

    default void onAgentProgress(AgentProgressEvent event) {
    }
}
