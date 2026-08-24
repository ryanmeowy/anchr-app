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
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskQueryService {
    private final AgentTaskRepository taskRepository;
    private final ConversationRepository conversationRepository;
    private final AgentTraceRepository traceRepository;
    private final AgentTaskProcessor taskProcessor;
    private final AnswerEventPublisher answerEventPublisher;
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
                if (!traceRepository.transitionRun(run, AgentRunStatus.WAITING_TASK.name())) {
                    log.warn("Agent task cancellation run transition ignored because status changed, taskId={}, runId={}",
                            task.getTaskId(), task.getRunId());
                }
            });
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            taskProcessor.recordCancellation(task);
            taskProcessor.interrupt(taskId);
        }
        AgentTask completedTask = requireAccessible(taskId);
        if (Boolean.TRUE.equals(cancelled)) {
            AnswerIdentity identity = AnswerIdentity.forTask(completedTask);
            answerEventPublisher.progress(identity, "CANCELLED", 100);
            answerEventPublisher.snapshot(identity, "任务已取消。");
            answerEventPublisher.citations(identity, List.of());
            answerEventPublisher.cancelled(identity);
        }
        return toDto(completedTask);
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
        AgentTaskDTO dto = new AgentTaskDTO(); dto.setTaskId(task.getTaskId());
        dto.setSessionId(task.getSessionId()); dto.setTurnId(task.getTurnId()); dto.setRunId(task.getRunId());
        dto.setRevision(Math.max(1, task.getAttemptCount())); dto.setType(task.getTaskType());
        dto.setStatus(task.getStatus()); dto.setProgress(task.getProgress()); dto.setCurrentStage(task.getCurrentStage());
        dto.setAnswer(task.getAnswer()); dto.setCitations(codec.parseCitations(task.getCitationsJson()));
        dto.setErrorCode(task.getErrorCode()); dto.setErrorMessage(task.getErrorMessage()); return dto;
    }
}
