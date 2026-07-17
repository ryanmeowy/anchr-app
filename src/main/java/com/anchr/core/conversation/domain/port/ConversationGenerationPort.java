package com.anchr.core.conversation.domain.port;

import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.ConversationGenerationResult;
import com.anchr.core.conversation.application.model.GenerationOptions;

import java.util.List;
import java.util.function.Consumer;

public interface ConversationGenerationPort {

    String generate(List<ConversationModelMessage> messages, GenerationOptions options);

    default ConversationGenerationResult generateWithUsage(List<ConversationModelMessage> messages,
                                                             GenerationOptions options) {
        return new ConversationGenerationResult(generate(messages, options), 0, 0);
    }

    /**
     * Streams model content deltas as they arrive. Implementations without
     * streaming support retain compatibility by emitting the completed result.
     */
    default ConversationGenerationResult generateStream(List<ConversationModelMessage> messages,
                                                         GenerationOptions options,
                                                         Consumer<String> onDelta) {
        ConversationGenerationResult result = generateWithUsage(messages, options);
        if (onDelta != null && result.content() != null && !result.content().isEmpty()) {
            onDelta.accept(result.content());
        }
        return result;
    }
}
