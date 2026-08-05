package com.anchr.core.conversation.application.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AgentWorkflowConfiguration {
    @Bean
    AgentTransitionEngine agentTransitionEngine() {
        return new AgentTransitionEngine();
    }
}
