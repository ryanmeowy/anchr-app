package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class AgentActionProtocol {
    private static final int MAX_PROTOCOL_ERRORS = 2;

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    AgentActionProtocol(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    ParsedAction parse(String raw) {
        try {
            String value = raw.trim();
            if (value.startsWith("```")) {
                int firstBreak = value.indexOf('\n');
                int lastFence = value.lastIndexOf("```");
                if (firstBreak > 0 && lastFence > firstBreak) {
                    value = value.substring(firstBreak + 1, lastFence).trim();
                }
            }
            JsonNode root = objectMapper.readTree(value);
            String action = root.path("action").asText();
            if ("final".equals(action)) {
                List<String> ids = new ArrayList<>();
                root.path("citedSegmentIds").forEach(node -> ids.add(node.asText()));
                AgentAnswerType answerType = parseAnswerType(root.path("answerType").asText(null));
                return new ParsedAction(List.of(), new AgentFinalAnswer(
                        answerType, root.path("answer").asText(), ids));
            }
            if ("call_tools".equals(action)) {
                List<AgentToolCall> calls = new ArrayList<>();
                for (JsonNode node : root.path("toolCalls")) {
                    JsonNode arguments = node.path("arguments");
                    calls.add(new AgentToolCall(node.path("id").asText(UUID.randomUUID().toString()),
                            node.path("name").asText(),
                            arguments.isTextual() ? arguments.asText() : arguments.toString()));
                }
                return new ParsedAction(calls, null);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    void resetErrors(AgentRunState state) {
        state.resetProtocolErrors();
    }

    int recordError(AgentRunState state, String code) {
        int errors = state.nextProtocolError();
        meterRegistry.counter("agent.protocol.error", "code", code,
                "outcome", errors >= MAX_PROTOCOL_ERRORS ? "fallback" : "retry").increment();
        return errors;
    }

    boolean shouldFallback(int errors) {
        return errors >= MAX_PROTOCOL_ERRORS;
    }

    private AgentAnswerType parseAnswerType(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return AgentAnswerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    record ParsedAction(List<AgentToolCall> toolCalls, AgentFinalAnswer finalAnswer) {
    }
}
