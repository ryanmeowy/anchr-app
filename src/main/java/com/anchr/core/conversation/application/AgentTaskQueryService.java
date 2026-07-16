package com.anchr.core.conversation.application;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.agent.AgentRunStatus;
import com.anchr.core.conversation.application.agent.AgentTaskProcessor;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AgentTaskQueryService {
    private final AgentTaskRepository taskRepository;
    private final ConversationRepository conversationRepository;
    private final AgentTraceRepository traceRepository;
    private final AgentTaskProcessor taskProcessor;
    private final ConversationTurnCodec codec;
    private final TransactionTemplate transactionTemplate;

    public AgentTaskDTO get(String taskId) {
        return toDto(requireAccessible(taskId));
    }

    public AgentTaskDTO cancel(String taskId) {
        AgentTask task = requireAccessible(taskId);
        if (isTerminal(task.getStatus())) return toDto(task);

        Boolean cancelled = transactionTemplate.execute(ignored -> {
            long now = System.currentTimeMillis();
            if (!taskRepository.cancel(task.getTaskId(), task.getUserId(), now)) return false;
            conversationRepository.findTurn(task.getSessionId(), task.getTurnId()).ifPresent(turn -> {
                turn.setAnswer("任务已取消。");
                turn.setCitationsJson("[]");
                turn.setAnswerStatus(AnswerStatus.CANCELLED.name());
                turn.setAnswerFallbackReason("agent_task_cancelled");
                conversationRepository.saveTurn(turn);
            });
            traceRepository.findRun(task.getRunId()).ifPresent(run -> {
                run.setStatus(AgentRunStatus.CANCELLED.name());
                run.setCurrentStep("TASK_CANCELLED");
                run.setErrorCode("TASK_CANCELLED");
                run.setFinishedAt(now);
                run.setLatencyMs(Math.max(0L, now - run.getStartedAt()));
                traceRepository.saveRun(run);
            });
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            taskProcessor.recordCancellation(task);
            taskProcessor.interrupt(taskId);
        }
        return get(taskId);
    }

    private AgentTask requireAccessible(String taskId) {
        AgentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        var session = conversationRepository.findSession(task.getSessionId())
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        if (!Objects.equals(session.getUserId(), task.getUserId())) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        return task;
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private AgentTaskDTO toDto(AgentTask task) {
        AgentTaskDTO dto = new AgentTaskDTO(); dto.setTaskId(task.getTaskId()); dto.setType(task.getTaskType());
        dto.setStatus(task.getStatus()); dto.setProgress(task.getProgress()); dto.setCurrentStage(task.getCurrentStage());
        dto.setAnswer(task.getAnswer()); dto.setCitations(codec.parseCitations(task.getCitationsJson()));
        dto.setErrorCode(task.getErrorCode()); dto.setErrorMessage(task.getErrorMessage()); return dto;
    }
}
