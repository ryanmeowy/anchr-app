package com.anchr.core.integration.ai.adapter;

import com.anchr.core.conversation.application.model.AgentModelOptions;
import com.anchr.core.conversation.application.model.AgentModelRequest;
import com.anchr.core.conversation.application.model.AgentToolDefinition;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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
