package com.anchr.core.conversation.domain.port;

import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.GenerationOptions;

import java.util.List;

public interface ConversationGenerationPort {

    String generate(List<ConversationModelMessage> messages, GenerationOptions options);
}
