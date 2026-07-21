package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.model.ChatResponseResult;

public interface ChatResponseService {

    ChatResponseResult generate(String sessionId, String query);

    default ChatResponseResult generateStream(String sessionId,
                                               String query,
                                               ConversationProgressListener progress) {
        ChatResponseResult result = generate(sessionId, query);
        if (progress != null && result.answer() != null) {
            progress.onAnswerDelta(result.answer());
        }
        return result;
    }
}
