package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ChatResponseResult;

public interface ChatResponseService {

    ChatResponseResult generate(String sessionId, String query);
}
