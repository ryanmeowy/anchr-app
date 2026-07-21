package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentRuntimeSnapshotDTO implements Serializable {
    private String runId;
    private String sessionId;
    private String turnId;
    private String status;
    private long version;
    private long updatedAt;
    private AgentRunActivityDTO activity;
    private ConversationMessageResponseDTO message;
    private AgentTaskDTO agentTask;
}
