package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.domain.model.ConversationCitation;

import java.util.List;

record AgentTerminal(String answer, AnswerStatus answerStatus, String fallbackReason,
                     List<ConversationCitation> citations, AgentDeferredTask deferredTask,
                     AgentRunStatus runStatus, RuntimeException cause) {
    AgentTerminal { citations = List.copyOf(citations == null ? List.of() : citations); }
}
