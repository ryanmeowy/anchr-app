package com.anchr.core.conversation.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionProtocolTest {

    @Test
    void fencedFinalAction_shouldPreserveAnswerTypeAnswerAndCitations() {
        AgentActionProtocol protocol = new AgentActionProtocol(
                new ObjectMapper(), new SimpleMeterRegistry());

        AgentActionProtocol.ParsedAction parsed = protocol.parse("""
                ```json
                {"action":"final","answerType":"knowledge","answer":"结论 {{segment:seg-1}}","citedSegmentIds":["seg-1"]}
                ```
                """);

        assertThat(parsed.toolCalls()).isEmpty();
        assertThat(parsed.finalAnswer().answerType()).isEqualTo(AgentAnswerType.KNOWLEDGE);
        assertThat(parsed.finalAnswer().answer()).isEqualTo("结论 {{segment:seg-1}}");
        assertThat(parsed.finalAnswer().citedSegmentIds()).containsExactly("seg-1");
    }

    @Test
    void toolActions_shouldPreserveOrderAndArgumentEncoding() {
        AgentActionProtocol protocol = new AgentActionProtocol(
                new ObjectMapper(), new SimpleMeterRegistry());

        AgentActionProtocol.ParsedAction parsed = protocol.parse("""
                {"action":"call_tools","toolCalls":[
                  {"id":"call-1","name":"search_knowledge","arguments":{"query":"权限"}},
                  {"id":"call-2","name":"read_document","arguments":"{\\"assetId\\":\\"asset-1\\"}"}
                ]}
                """);

        assertThat(parsed.finalAnswer()).isNull();
        assertThat(parsed.toolCalls()).extracting(call -> call.name())
                .containsExactly("search_knowledge", "read_document");
        assertThat(parsed.toolCalls().get(0).arguments()).isEqualTo("{\"query\":\"权限\"}");
        assertThat(parsed.toolCalls().get(1).arguments()).isEqualTo("{\"assetId\":\"asset-1\"}");
    }

    @Test
    void protocolErrors_shouldKeepRetryFallbackThresholdAndResetBehavior() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentActionProtocol protocol = new AgentActionProtocol(new ObjectMapper(), registry);
        AgentRunState state = new AgentRunState(
                null, new AgentBudget(6, 4, System.currentTimeMillis() + 10_000L),
                System.currentTimeMillis());

        int first = protocol.recordError(state, "MISSING_ACTION");
        int second = protocol.recordError(state, "MISSING_ACTION");
        protocol.resetErrors(state);
        int afterReset = protocol.recordError(state, "MISSING_ACTION");

        assertThat(protocol.shouldFallback(first)).isFalse();
        assertThat(protocol.shouldFallback(second)).isTrue();
        assertThat(protocol.shouldFallback(afterReset)).isFalse();
        assertThat(registry.get("agent.protocol.error")
                .tags("code", "MISSING_ACTION", "outcome", "retry")
                .counter().count()).isEqualTo(2D);
        assertThat(registry.get("agent.protocol.error")
                .tags("code", "MISSING_ACTION", "outcome", "fallback")
                .counter().count()).isEqualTo(1D);
    }
}
