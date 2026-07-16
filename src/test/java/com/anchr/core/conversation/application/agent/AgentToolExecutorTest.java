package com.anchr.core.conversation.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolExecutorTest {
    @Test
    void shouldDistinguishUnknownToolFromInvalidToolArguments() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new InvalidInputTool()));
        AgentToolExecutor executor = new AgentToolExecutor(registry, new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator());
        AgentExecutionContext context = new AgentExecutionContext("run", "turn", "session", "user",
                List.of("kb"), List.of(), new AgentBudget(12, 8, System.currentTimeMillis() + 10_000));

        AgentToolResult unknown = executor.execute("missing", "{}", context);
        AgentToolResult invalid = executor.execute("invalid_input", "{}", context);

        assertThat(unknown.errorCode()).isEqualTo("UNKNOWN_TOOL");
        assertThat(invalid.errorCode()).isEqualTo("INVALID_ARGUMENTS");
    }

    record Input() {}
    static class InvalidInputTool implements AgentTool<Input> {
        public String name() { return "invalid_input"; }
        public String description() { return "test"; }
        public Class<Input> inputType() { return Input.class; }
        public AgentToolResult execute(Input input, AgentExecutionContext context) {
            throw new IllegalArgumentException("bad document reference");
        }
    }
}
