package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ConversationIntentResult;

public interface ConversationIntentRouter {

    ConversationIntentResult route(String sessionId, String query);
}
