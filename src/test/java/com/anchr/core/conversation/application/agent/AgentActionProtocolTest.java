package com.anchr.core.conversation.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionProtocolTest {

    @Test
    void fencedFinalAction_shouldPreserveAnswerTypeAnswerAndCitations() {
        AgentActionProtocol protocol = new AgentActionProtocol(new ObjectMapper());

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
        AgentActionProtocol protocol = new AgentActionProtocol(new ObjectMapper());

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
        AgentActionProtocol protocol = new AgentActionProtocol(new ObjectMapper());

        assertThat(protocol.parse("not-json"))
                .isInstanceOf(AgentActionProtocol.ParseOutcome.Invalid.class);
    }

}
