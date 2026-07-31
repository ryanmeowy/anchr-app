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

        AgentActionProtocol.ParseOutcome parsed = protocol.parse("""
                ```json
                {"action":"final","answerType":"knowledge","answer":"结论 {{segment:seg-1}}","citedSegmentIds":["seg-1"]}
                ```
                """);

        assertThat(parsed).isInstanceOf(AgentActionProtocol.ParseOutcome.FinalAnswer.class);
        AgentFinalAnswer answer = ((AgentActionProtocol.ParseOutcome.FinalAnswer) parsed).answer();
        assertThat(answer.answerType()).isEqualTo(AgentAnswerType.KNOWLEDGE);
        assertThat(answer.answer()).isEqualTo("结论 {{segment:seg-1}}");
        assertThat(answer.citedSegmentIds()).containsExactly("seg-1");
    }

    @Test
    void toolActions_shouldPreserveOrderAndArgumentEncoding() {
        AgentActionProtocol protocol = new AgentActionProtocol(
                new ObjectMapper(), new SimpleMeterRegistry());

        AgentActionProtocol.ParseOutcome parsed = protocol.parse("""
                {"action":"call_tools","toolCalls":[
                  {"id":"call-1","name":"search_knowledge","arguments":{"query":"权限"}},
                  {"id":"call-2","name":"read_document","arguments":"{\\"assetId\\":\\"asset-1\\"}"}
                ]}
                """);

        assertThat(parsed).isInstanceOf(AgentActionProtocol.ParseOutcome.ToolCalls.class);
        var calls = ((AgentActionProtocol.ParseOutcome.ToolCalls) parsed).calls();
        assertThat(calls).extracting(call -> call.name())
                .containsExactly("search_knowledge", "read_document");
        assertThat(calls.get(0).arguments()).isEqualTo("{\"query\":\"权限\"}");
        assertThat(calls.get(1).arguments()).isEqualTo("{\"assetId\":\"asset-1\"}");
    }

    @Test
    void invalidProtocolOutput_shouldReturnExplicitOutcome() {
        AgentActionProtocol protocol = new AgentActionProtocol(
                new ObjectMapper(), new SimpleMeterRegistry());

        assertThat(protocol.parse("not-json"))
                .isInstanceOf(AgentActionProtocol.ParseOutcome.Invalid.class);
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
