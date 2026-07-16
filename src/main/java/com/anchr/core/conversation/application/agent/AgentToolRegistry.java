package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool<?>> tools;

    public AgentToolRegistry(List<AgentTool<?>> registeredTools) {
        Map<String, AgentTool<?>> values = new LinkedHashMap<>();
        for (AgentTool<?> tool : registeredTools) {
            if (values.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate agent tool: " + tool.name());
            }
        }
        this.tools = Map.copyOf(values);
    }

    public AgentTool<?> require(String name) {
        AgentTool<?> tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown_tool:" + name);
        return tool;
    }

    public List<AgentToolDefinition> definitions() {
        return tools.values().stream().map(tool -> new AgentToolDefinition(
                tool.name(), tool.description(), JsonSchemaGenerator.generateForType(tool.inputType()))).toList();
    }
}
