package com.anchr.core.conversation.application.agent;

import lombok.Getter;

@Getter
public class AgentToolException extends RuntimeException {
    private final String code;

    public AgentToolException(String code, String message) {
        super(message);
        this.code = code;
    }
}
