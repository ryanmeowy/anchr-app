package com.anchr.core.conversation.application;

import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/** Transport-neutral event in one answer lifecycle. */
public record AnswerEvent(
        Type type,
        AnswerIdentity identity,
        long sequence,
        String stage,
        Integer progress,
        String text,
        List<ConversationCitation> citations,
        String errorCode
) {
    public AnswerEvent {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public enum Type {
        STARTED, PROGRESS, DELTA, SNAPSHOT, CITATIONS, COMPLETED, FAILED, CANCELLED
    }
}
