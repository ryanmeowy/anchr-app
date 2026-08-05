package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentModelOptions;
import com.anchr.core.conversation.application.model.AgentModelRequest;
import com.anchr.core.conversation.application.model.AgentModelResponse;
import com.anchr.core.conversation.application.model.AgentToolCall;
import com.anchr.core.conversation.domain.port.AgentModelPort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_MAX_TOKENS;
import static com.anchr.core.conversation.application.constant.AgentConstant.PLANNING_TEMPERATURE;

@Component
class AgentModelEffect {
    private final AgentModelPort modelPort;
    private final AgentToolRegistry toolRegistry;
    private final AgentActionProtocol actionProtocol;

    AgentModelEffect(AgentModelPort modelPort,
                     AgentToolRegistry toolRegistry,
                     AgentActionProtocol actionProtocol) {
        this.modelPort = modelPort;
        this.toolRegistry = toolRegistry;
        this.actionProtocol = actionProtocol;
    }

    AgentEvent execute(AgentState state, AgentCommand.CallModel command) {
        long started = System.currentTimeMillis();
        try {
            AgentModelRequest request = new AgentModelRequest(state.messages(),
                    command.toolsEnabled() ? toolRegistry.definitions() : List.of(),
                    new AgentModelOptions(PLANNING_TEMPERATURE, PLANNING_MAX_TOKENS,
                            command.timeout(), command.toolCallMode(),
                            state.runtimeConfig().nativeToolChoice().name(), command.toolsEnabled()));
            AgentModelResponse response = modelPort.respond(request);
            AgentModelDecision decision = semanticDecision(state, response);
            long ended = System.currentTimeMillis();
            return new AgentEvent.ModelCompleted(response, decision, ended - started, ended);
        } catch (RuntimeException e) {
            long ended = System.currentTimeMillis();
            return new AgentEvent.ModelFailed(e, ended - started, ended);
        }
    }

    private AgentModelDecision semanticDecision(AgentState state, AgentModelResponse response) {
        List<AgentToolCall> calls = response.toolCalls();
        if (!calls.isEmpty()) return new AgentModelDecision.ToolCalls(calls);
        if (!StringUtils.hasText(response.content())
                || state.runtimeConfig().toolCallMode() == AgentRuntimeSettings.ToolCallMode.NATIVE) {
            return new AgentModelDecision.ProtocolError("MISSING_ACTION");
        }
        AgentActionProtocol.ParseOutcome parsed = actionProtocol.parse(response.content());
        if (parsed instanceof AgentActionProtocol.ParseOutcome.ToolCalls parsedCalls) {
            return new AgentModelDecision.ToolCalls(parsedCalls.calls());
        }
        if (parsed instanceof AgentActionProtocol.ParseOutcome.FinalAnswer finalAnswer) {
            return new AgentModelDecision.FinalAnswer(finalAnswer.answer());
        }
        return new AgentModelDecision.ProtocolError("MISSING_ACTION");
    }
}
