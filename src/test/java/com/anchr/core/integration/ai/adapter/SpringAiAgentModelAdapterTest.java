package com.anchr.core.integration.ai.adapter;

import com.anchr.core.conversation.application.model.AgentModelOptions;
import com.anchr.core.conversation.application.model.AgentModelRequest;
import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.AgentToolCall;
import com.anchr.core.conversation.application.model.AgentToolDefinition;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiAgentModelAdapterTest {

    @Test
    void buildOptions_shouldApplyConfiguredNativeToolChoice() {
        SpringAiAgentModelAdapter adapter = new SpringAiAgentModelAdapter(null, null, new ObjectMapper());
        CapabilityConfig config = CapabilityConfig.builder().modelName("qwen-plus").build();
        AgentToolDefinition tool = new AgentToolDefinition(
                "deliver_answer", "提交最终回答", "{\"type\":\"object\"}");
        try {
            var required = adapter.buildOptions(config, request(tool, "REQUIRED"), true);
            var auto = adapter.buildOptions(config, request(tool, "AUTO"), true);
            var disabled = adapter.buildOptions(config, request(tool, "REQUIRED"), false);

            assertThat(required.getToolChoice()).isEqualTo("required");
            assertThat(auto.getToolChoice()).isEqualTo("auto");
            assertThat(disabled.getToolChoice()).isNull();
            assertThat(required.getTools()).hasSize(1);
            assertThat(required.getTools().getFirst().getFunction().getName()).isEqualTo("deliver_answer");
            assertThat(required.getTools().getFirst().getFunction().getDescription()).isEqualTo("提交最终回答");
        } finally {
            adapter.close();
        }
    }

    @Test
    void buildOptions_shouldForwardProviderOptionsWithoutInspectingModelName() {
        SpringAiAgentModelAdapter adapter = new SpringAiAgentModelAdapter(null, null, new ObjectMapper());
        CapabilityConfig config = CapabilityConfig.builder()
                .modelName("any-provider-model")
                .extraConfig("{\"temperature\":0.5,\"thinking\":{\"type\":\"enabled\"}}")
                .build();
        AgentToolDefinition tool = new AgentToolDefinition(
                "deliver_answer", "提交最终回答", "{\"type\":\"object\"}");
        try {
            var options = adapter.buildOptions(config, request(tool, "REQUIRED"), true);

            assertThat(options.getToolChoice()).isEqualTo("required");
            assertThat(options.getExtraBody()).isEqualTo(
                    Map.of("thinking", Map.of("type", "enabled")));
            assertThat(options.getTools().getFirst().getFunction().getName()).isEqualTo("deliver_answer");
        } finally {
            adapter.close();
        }
    }

    @Test
    void toApiMessages_shouldReplayReasoningContentForToolCalls() {
        SpringAiAgentModelAdapter adapter = new SpringAiAgentModelAdapter(null, null, new ObjectMapper());
        try {
            var messages = adapter.toApiMessages(List.of(AgentMessage.assistantToolCalls(
                    "", "reasoning", List.of(new AgentToolCall(
                            "call-1", "search_knowledge", "{\"query\":\"test\"}")))));

            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().reasoningContent()).isEqualTo("reasoning");
            assertThat(messages.getFirst().toolCalls().getFirst().function().name())
                    .isEqualTo("search_knowledge");
        } finally {
            adapter.close();
        }
    }

    private AgentModelRequest request(AgentToolDefinition tool, String nativeToolChoice) {
        return new AgentModelRequest(List.of(), List.of(tool),
                new AgentModelOptions(0.2, 1_500, Duration.ofSeconds(30),
                        "AUTO", nativeToolChoice, true));
    }
}
