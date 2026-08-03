package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class ConversationAgentTaskDtoAssembler {

    private final ConversationTurnCodec turnCodec;

    AgentTaskDTO toDto(AgentTask task) {
        if (task == null) {
            return null;
        }
        AgentTaskDTO dto = new AgentTaskDTO();
        dto.setTaskId(task.getTaskId());
        dto.setSessionId(task.getSessionId());
        dto.setTurnId(task.getTurnId());
        dto.setRunId(task.getRunId());
        dto.setRevision(Math.max(1, task.getAttemptCount()));
        dto.setType(task.getTaskType());
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress());
        dto.setCurrentStage(task.getCurrentStage());
        dto.setAnswer(task.getAnswer());
        dto.setCitations(turnCodec.parseCitations(task.getCitationsJson()));
        dto.setErrorCode(task.getErrorCode());
        dto.setErrorMessage(task.getErrorMessage());
        return dto;
    }
}
