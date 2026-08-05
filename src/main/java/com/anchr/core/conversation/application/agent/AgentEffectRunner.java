package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import org.springframework.stereotype.Component;

@Component
public class AgentEffectRunner {
    private final AgentModelEffect modelEffect;
    private final AgentToolEffect toolEffect;
    private final AgentCompletionEffect completionEffect;

    public AgentEffectRunner(AgentModelEffect modelEffect,
                             AgentToolEffect toolEffect,
                             AgentCompletionEffect completionEffect) {
        this.modelEffect = modelEffect;
        this.toolEffect = toolEffect;
        this.completionEffect = completionEffect;
    }

    public AgentEvent execute(AgentState state, AgentCommand command,
                              ConversationProgressListener progress) {
        if (command instanceof AgentCommand.CallModel model) return modelEffect.execute(state, model);
        if (command instanceof AgentCommand.CallTool tool) return toolEffect.execute(state, tool);
        return completionEffect.execute(state, command, progress);
    }
}
