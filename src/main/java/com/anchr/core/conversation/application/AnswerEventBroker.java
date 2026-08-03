package com.anchr.core.conversation.application;

import java.util.function.Consumer;

public interface AnswerEventBroker extends AnswerEventPublisher {
    Subscription subscribe(String channelId, Consumer<AnswerEvent> consumer);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
