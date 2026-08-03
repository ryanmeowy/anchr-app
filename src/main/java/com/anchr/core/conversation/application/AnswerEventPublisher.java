package com.anchr.core.conversation.application;

import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

/** Application output port shared by synchronous and background answer lifecycles. */
public interface AnswerEventPublisher {
    void started(AnswerIdentity identity);

    void progress(AnswerIdentity identity, String stage, int progress);

    void delta(AnswerIdentity identity, String text);

    void snapshot(AnswerIdentity identity, String canonicalAnswer);

    void citations(AnswerIdentity identity, List<ConversationCitation> citations);

    void completed(AnswerIdentity identity);

    void failed(AnswerIdentity identity, String errorCode);

    void cancelled(AnswerIdentity identity);
}
