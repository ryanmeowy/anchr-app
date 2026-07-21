package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskDTO implements Serializable {
    private String taskId;
    private String type;
    private String status;
    private int progress;
    private String currentStage;
    private String answer;
    private List<ConversationTurnDTO.CitationDTO> citations;
    private String errorCode;
    private String errorMessage;
}
