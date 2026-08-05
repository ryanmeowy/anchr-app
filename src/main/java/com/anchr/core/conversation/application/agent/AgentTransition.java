package com.anchr.core.conversation.application.agent;

import java.util.List;

record AgentTransition(AgentState nextState, AgentCommand command,
                       List<AgentSignal> signals, AgentTerminal terminal) {
    AgentTransition { signals = List.copyOf(signals == null ? List.of() : signals); }
}
