package com.anchr.core.conversation.application;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunActivityDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunSummaryDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.anchr.core.conversation.application.constant.AgentConstant.AGENT_ACTIVITY_MAX_STEPS;
import static com.anchr.core.conversation.application.constant.AgentConstant.AGENT_ACTIVITY_DEFAULT_STEP_LIMIT;
import static com.anchr.core.conversation.application.constant.ConversationConstant.SINGLE_USER_ID;

@Service
@RequiredArgsConstructor
public class AgentRunActivityService {
    private final AgentTraceRepository traceRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    public AgentRunActivityDTO get(String runId) {
        AgentRun run = requireAccessibleRun(runId);
        AgentRunActivityDTO dto = new AgentRunActivityDTO();
        dto.setRunId(run.getRunId());
        dto.setSessionId(run.getSessionId());
        dto.setTurnId(run.getTurnId());
        dto.setStatus(activityStatus(run.getStatus()));
        dto.setCurrentStep(run.getCurrentStep());
        dto.setToolCallCount(run.getToolCallCount());
        dto.setPromptTokens(run.getPromptTokens());
        dto.setCompletionTokens(run.getCompletionTokens());
        dto.setLatencyMs(elapsedTime(run));
        dto.setFallbackReason(run.getFallbackReason());
        dto.setStartedAt(run.getStartedAt());
        dto.setFinishedAt(run.getFinishedAt());

        List<AgentRunActivityDTO.StepDTO> steps = new ArrayList<>(traceRepository.findSteps(runId).stream()
                .sorted(Comparator.comparingInt(AgentStep::getStepOrder))
                .limit(AGENT_ACTIVITY_MAX_STEPS)
                .map(this::toStep)
                .toList());
        boolean hasFinalStep = steps.stream().anyMatch(step -> "FINAL".equals(step.getType()));
        if (isTerminal(dto.getStatus()) && !hasFinalStep && steps.size() < AGENT_ACTIVITY_MAX_STEPS) {
            steps.add(finalStep(run, steps));
        }
        dto.setStepCount(steps.size());
        dto.setSteps(steps);
        return dto;
    }

    public void verifyAccessible(String runId) {
        requireAccessibleRun(runId);
    }

    public List<AgentRunSummaryDTO> listRecoverable(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, AGENT_ACTIVITY_DEFAULT_STEP_LIMIT));
        return traceRepository.findRecoverableRuns(SINGLE_USER_ID, boundedLimit).stream()
                .map(this::toSummary)
                .toList();
    }

    private AgentRunSummaryDTO toSummary(AgentRun run) {
        AgentRunSummaryDTO dto = new AgentRunSummaryDTO();
        dto.setRunId(run.getRunId());
        dto.setSessionId(run.getSessionId());
        dto.setTurnId(run.getTurnId());
        dto.setStatus(activityStatus(run.getStatus()));
        dto.setCurrentStep(run.getCurrentStep());
        dto.setStartedAt(run.getStartedAt());
        dto.setFinishedAt(run.getFinishedAt());
        return dto;
    }

    private AgentRun requireAccessibleRun(String runId) {
        AgentRun run = traceRepository.findRun(runId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        if (conversationRepository.findSession(run.getSessionId()).isEmpty()) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        return run;
    }

    private AgentRunActivityDTO.StepDTO toStep(AgentStep source) {
        Map<String, Object> input = parse(source.getInputSummaryJson());
        Map<String, Object> output = parse(source.getOutputSummaryJson());
        AgentRunActivityDTO.StepDTO target = new AgentRunActivityDTO.StepDTO();
        target.setStepOrder(source.getStepOrder());
        target.setType(stepType(source.getStepType()));
        target.setToolName(text(output.get("tool"), text(input.get("tool"), null)));
        target.setCallId(text(output.get("callId"), text(input.get("callId"), null)));
        String legacyTaskStage = text(output.get("taskStage"), text(input.get("taskStage"), null));
        target.setTaskStage("TASK_STAGE".equals(source.getStepType())
                ? text(source.getDecisionCode(), legacyTaskStage) : legacyTaskStage);
        target.setTaskType(text(output.get("taskType"), null));
        target.setAnswerType(text(output.get("answerType"), null));
        target.setModel(text(output.get("model"), null));
        target.setDecision(decision(source.getStepType(), output));
        target.setStatus(source.getStatus());
        target.setAttempt(source.getAttempt());
        Integer progress = integer(output.get("progress"));
        target.setProgress("TASK_STAGE".equals(source.getStepType())
                && "COMPLETED".equals(source.getStatus()) && progress != null
                ? Integer.valueOf(100) : progress);
        target.setMessageCount(integer(input.get("messageCount")));
        target.setPlannedToolCallCount(integer(output.get("toolCallCount")));
        target.setEvidenceCount(integer(output.get("evidenceCount")));
        target.setDocumentCount(integer(output.get("documentCount")));
        target.setSegmentCount(integer(output.get("segmentCount")));
        target.setBatchCount(integer(output.get("batchCount")));
        target.setCitationCount(integer(output.get("citationCount")));
        target.setHasMore(bool(output.get("hasMore")));
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setModelCallCount(integer(output.get("modelCallCount")));
        target.setModelLatencyMs(longValue(output.get("modelLatencyMs")));
        target.setFirstTokenMs(longValue(output.get("firstTokenMs")));
        target.setStreaming(bool(output.get("streaming")));
        target.setDurationMs("RUNNING".equals(source.getStatus()) && source.getCreatedAt() > 0
                ? Math.max(source.getLatencyMs(), System.currentTimeMillis() - source.getCreatedAt())
                : source.getLatencyMs());
        target.setCreatedAt(source.getCreatedAt());
        target.setErrorCode(source.getErrorCode());
        return target;
    }

    private String decision(String stepType, Map<String, Object> output) {
        String explicitDecision = text(output.get("decision"), null);
        if (!"MODEL_DECISION".equals(stepType)) return explicitDecision;
        Integer toolCalls = integer(output.get("toolCallCount"));
        if (toolCalls != null && toolCalls > 0) return "TOOL_SELECTION";
        return explicitDecision != null
                ? explicitDecision
                : Boolean.TRUE.equals(bool(output.get("hasContent"))) ? "FINAL_RESPONSE" : "PROTOCOL_RETRY";
    }

    private AgentRunActivityDTO.StepDTO finalStep(AgentRun run, List<AgentRunActivityDTO.StepDTO> steps) {
        AgentRunActivityDTO.StepDTO target = new AgentRunActivityDTO.StepDTO();
        int maxOrder = steps.stream().mapToInt(AgentRunActivityDTO.StepDTO::getStepOrder).max().orElse(0);
        target.setStepOrder(maxOrder + 1);
        target.setType("FINAL");
        target.setStatus(finalStepStatus(run.getStatus()));
        target.setAttempt(1);
        target.setDurationMs(run.getLatencyMs());
        target.setCreatedAt(run.getFinishedAt() == null ? run.getStartedAt() + run.getLatencyMs() : run.getFinishedAt());
        target.setErrorCode(run.getErrorCode());
        return target;
    }

    private Map<String, Object> parse(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String stepType(String value) {
        if ("MODEL_DECISION".equals(value)) return "MODEL_DECISION";
        if ("TASK_STAGE".equals(value)) return "TASK_STAGE";
        if ("TOOL_RESULT".equals(value) || "FAILED".equals(value)) return "TOOL";
        return "FINAL_ANSWER".equals(value) ? "FINAL" : value;
    }

    private String activityStatus(String value) {
        if ("FALLBACK".equals(value)) return "AGENT_FALLBACK";
        return "DEGRADED".equals(value) ? "AGENT_DEGRADED" : value;
    }

    private boolean isTerminal(String value) {
        return List.of("COMPLETED", "FAILED", "CANCELLED", "AGENT_DEGRADED", "AGENT_FALLBACK").contains(value);
    }

    private String finalStepStatus(String runStatus) {
        if ("FAILED".equals(runStatus)) return "FAILED";
        if ("CANCELLED".equals(runStatus)) return "CANCELLED";
        return "COMPLETED";
    }

    private String text(Object value, String fallback) {
        return value instanceof String text && StringUtils.hasText(text) ? text : fallback;
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private long elapsedTime(AgentRun run) {
        if (run.getStartedAt() <= 0) return Math.max(0L, run.getLatencyMs());
        long end = run.getFinishedAt() == null ? System.currentTimeMillis() : run.getFinishedAt();
        return Math.max(0L, end - run.getStartedAt());
    }
}
