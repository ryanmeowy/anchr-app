package com.anchr.core.conversation.application.agent;

public interface AgentTool<I> {
    String name();
    String description();
    Class<I> inputType();
    AgentToolResult execute(I input, AgentExecutionContext context);
}
