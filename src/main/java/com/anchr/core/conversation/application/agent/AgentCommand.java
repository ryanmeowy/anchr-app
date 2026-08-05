package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentToolCall;

import java.time.Duration;

sealed interface AgentCommand permits AgentCommand.CallModel, AgentCommand.CallTool,
        AgentCommand.VerifyAnswer, AgentCommand.CallEvidenceFinalizer, AgentCommand.PresentAnswer {

    record CallModel(boolean toolsEnabled, String toolCallMode, int attempt, int stepOrder,
                     long issuedAt, Duration timeout) implements AgentCommand {}
    record CallTool(AgentToolCall call, int attempt, int stepOrder,
                    long issuedAt) implements AgentCommand {}
    record VerifyAnswer(UnverifiedAgentAnswer answer) implements AgentCommand {}
    record CallEvidenceFinalizer(int attempt, int stepOrder, String trigger,
                                 String lastInvalid, long issuedAt,
                                 Duration timeout) implements AgentCommand {}
    record PresentAnswer(VerifiedAgentAnswer answer, boolean modelAttempt,
                         int attempt, int stepOrder, long issuedAt,
                         Duration timeout) implements AgentCommand {}
}
