package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunActivityDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRuntimeSnapshotDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Best-effort cache of the latest running Agent state. Conversation and trace tables remain authoritative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeSnapshotService {
    private static final String SNAPSHOT_KEY_PREFIX = "anchr:agent:runtime:snapshot:";
    private static final String VERSION_KEY_PREFIX = "anchr:agent:runtime:version:";
    private static final Duration SNAPSHOT_SAFETY_MARGIN = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRunActivityService activityService;
    private final ConversationTurnCodec turnCodec;
    private final RuntimeConfigUnit runtimeConfigUnit;

    public AgentRuntimeSnapshotDTO get(String runId, long afterVersion) {
        if (!StringUtils.hasText(runId)) return null;
        try {
            String value = redisTemplate.opsForValue().get(snapshotKey(runId));
            if (!StringUtils.hasText(value)) return null;
            AgentRuntimeSnapshotDTO snapshot = objectMapper.readValue(value, AgentRuntimeSnapshotDTO.class);
            return snapshot.getVersion() > Math.max(0L, afterVersion) ? snapshot : null;
        } catch (Exception e) {
            log.debug("Agent runtime snapshot read failed, runId={}", runId, e);
            return null;
        }
    }

    public void publishActivity(String runId) {
        publish(runId, null, null, null);
    }

    public void publishProgress(AgentProgressEvent event) {
        if (event == null) return;
        publish(event.runId(), null, null, event);
    }

    public void publishMessage(String runId, ConversationMessageResponseDTO message) {
        publish(runId, message, message == null ? null : message.getAgentTask(), null);
    }

    public void publishTask(String runId, AgentTask task) {
        publish(runId, null, toTaskDto(task), null);
    }

    Duration effectiveTtl() {
        Duration configured = positive(
                runtimeConfigUnit.getDurationSeconds(
                        RuntimeConfigType.AGENT,
                        AgentRuntimeConfigKey.RUNTIME_SNAPSHOT_TTL_SECONDS,
                        Duration.ofMinutes(35)),
                Duration.ofMinutes(35));
        Duration attemptTimeout = positive(
                runtimeConfigUnit.getDurationSeconds(
                        RuntimeConfigType.AGENT,
                        AgentRuntimeConfigKey.TASK_TIMEOUT_SECONDS,
                        Duration.ofMinutes(10)),
                Duration.ofMinutes(10));
        int attempts = Math.max(1,
                runtimeConfigUnit.getInt(
                        RuntimeConfigType.AGENT,
                        AgentRuntimeConfigKey.TASK_MAX_RETRIES,
                        2) + 1);
        Duration minimum = attemptTimeout.multipliedBy(attempts).plus(SNAPSHOT_SAFETY_MARGIN);
        return configured.compareTo(minimum) >= 0 ? configured : minimum;
    }

    private void publish(String runId,
                         ConversationMessageResponseDTO message,
                         AgentTaskDTO agentTask,
                         AgentProgressEvent progressEvent) {
        if (!StringUtils.hasText(runId)) return;
        try {
            AgentRunActivityDTO activity = activityService.get(runId);
            AgentRuntimeSnapshotDTO previous = readQuietly(runId);
            mergeRuntimeSteps(activity, previous == null ? null : previous.getActivity(), progressEvent);
            Long incremented = redisTemplate.opsForValue().increment(versionKey(runId));
            long previousVersion = previous == null ? 0L : previous.getVersion();
            long version = Math.max(previousVersion + 1L,
                    incremented == null ? Math.max(1L, System.currentTimeMillis()) : incremented);

            AgentRuntimeSnapshotDTO snapshot = new AgentRuntimeSnapshotDTO();
            snapshot.setRunId(runId);
            snapshot.setSessionId(activity.getSessionId());
            snapshot.setTurnId(activity.getTurnId());
            snapshot.setStatus(activity.getStatus());
            snapshot.setVersion(version);
            snapshot.setUpdatedAt(System.currentTimeMillis());
            snapshot.setActivity(activity);
            snapshot.setMessage(message != null ? message : previous == null ? null : previous.getMessage());
            snapshot.setAgentTask(selectAgentTask(previous == null ? null : previous.getAgentTask(), agentTask));

            Duration ttl = effectiveTtl();
            redisTemplate.opsForValue().set(snapshotKey(runId), objectMapper.writeValueAsString(snapshot), ttl);
            redisTemplate.opsForValue().set(versionKey(runId), String.valueOf(version), ttl);
        } catch (Exception e) {
            // Runtime recovery must never fail the canonical Agent execution path.
            log.warn("Agent runtime snapshot write failed, runId={}", runId, e);
        }
    }

    private AgentRuntimeSnapshotDTO readQuietly(String runId) {
        try {
            String value = redisTemplate.opsForValue().get(snapshotKey(runId));
            return StringUtils.hasText(value)
                    ? objectMapper.readValue(value, AgentRuntimeSnapshotDTO.class)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentTaskDTO toTaskDto(AgentTask task) {
        if (task == null) return null;
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

    private AgentTaskDTO selectAgentTask(AgentTaskDTO previous, AgentTaskDTO incoming) {
        if (incoming == null) return previous;
        if (previous == null) return incoming;
        return terminalTask(previous.getStatus()) && !terminalTask(incoming.getStatus()) ? previous : incoming;
    }

    private boolean terminalTask(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private void mergeRuntimeSteps(AgentRunActivityDTO activity,
                                   AgentRunActivityDTO previous,
                                   AgentProgressEvent event) {
        List<AgentRunActivityDTO.StepDTO> merged = new ArrayList<>();
        if (previous != null && previous.getSteps() != null) {
            for (AgentRunActivityDTO.StepDTO step : previous.getSteps()) mergeStep(merged, step, true);
        }
        if (activity.getSteps() != null) {
            for (AgentRunActivityDTO.StepDTO step : activity.getSteps()) mergeStep(merged, step, true);
        }
        AgentRunActivityDTO.StepDTO transientStep = progressStep(event);
        if (transientStep != null) mergeStep(merged, transientStep, false);
        merged.sort(Comparator.comparingInt(AgentRunActivityDTO.StepDTO::getStepOrder));
        if (merged.size() > 50) merged = new ArrayList<>(merged.subList(merged.size() - 50, merged.size()));
        activity.setSteps(merged);
        activity.setStepCount(merged.size());
    }

    private void mergeStep(List<AgentRunActivityDTO.StepDTO> steps,
                           AgentRunActivityDTO.StepDTO next,
                           boolean replaceExisting) {
        if (next == null || next.getStepOrder() <= 0) return;
        for (int index = 0; index < steps.size(); index++) {
            AgentRunActivityDTO.StepDTO current = steps.get(index);
            if (current.getStepOrder() == next.getStepOrder()
                    && java.util.Objects.equals(current.getType(), next.getType())) {
                if (replaceExisting) steps.set(index, next);
                return;
            }
        }
        steps.add(next);
    }

    private AgentRunActivityDTO.StepDTO progressStep(AgentProgressEvent event) {
        if (event == null || "run_started".equals(event.message())) return null;
        if (!List.of("agent_thinking", "tool_call", "tool_result", "task_queued").contains(event.stage())) {
            return null;
        }
        Map<String, Object> details = event.details() == null ? Map.of() : event.details();
        Integer stepOrder = integer(details.get("stepOrder"));
        if (stepOrder == null || stepOrder <= 0) return null;

        boolean tool = "tool_call".equals(event.stage()) || "tool_result".equals(event.stage());
        boolean started = "tool_call".equals(event.stage())
                || "started".equals(event.message())
                || (event.message() != null && event.message().endsWith("_started"));
        Boolean success = bool(details.get("success"));

        AgentRunActivityDTO.StepDTO step = new AgentRunActivityDTO.StepDTO();
        step.setStepOrder(stepOrder);
        step.setType(tool ? "TOOL" : "task_queued".equals(event.stage()) ? "TASK_STAGE" : "MODEL_DECISION");
        step.setToolName(text(details.get("tool")));
        step.setCallId(text(details.get("callId")));
        step.setTaskStage("task_queued".equals(event.stage()) ? "QUEUED" : null);
        step.setTaskType(text(details.get("taskType")));
        step.setAnswerType(text(details.get("answerType")));
        step.setModel(text(details.get("model")));
        step.setDecision(text(details.get("decision")));
        step.setStatus(started ? "RUNNING" : Boolean.FALSE.equals(success) ? "FAILED" : "COMPLETED");
        step.setAttempt(integer(details.get("attempt")) == null ? event.attempt() : integer(details.get("attempt")));
        step.setProgress(integer(details.get("progress")));
        step.setMessageCount(integer(details.get("messageCount")));
        step.setPlannedToolCallCount(integer(details.get("toolCallCount")));
        step.setEvidenceCount(integer(details.get("evidenceCount")));
        step.setDocumentCount(integer(details.get("documentCount")));
        step.setSegmentCount(integer(details.get("segmentCount")));
        step.setBatchCount(integer(details.get("batchCount")));
        step.setCitationCount(integer(details.get("citationCount")));
        step.setHasMore(bool(details.get("hasMore")));
        Integer promptTokens = integer(details.get("promptTokens"));
        Integer completionTokens = integer(details.get("completionTokens"));
        step.setPromptTokens(promptTokens == null ? 0 : promptTokens);
        step.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
        step.setModelCallCount(integer(details.get("modelCallCount")));
        step.setModelLatencyMs(longValue(details.get("modelLatencyMs")));
        step.setFirstTokenMs(longValue(details.get("firstTokenMs")));
        step.setStreaming(bool(details.get("streaming")));
        Long durationMs = longValue(details.get("durationMs"));
        step.setDurationMs(durationMs == null ? 0L : durationMs);
        step.setCreatedAt(System.currentTimeMillis());
        step.setErrorCode(text(details.get("errorCode")));
        return step;
    }

    private String text(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
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

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private String snapshotKey(String runId) {
        return SNAPSHOT_KEY_PREFIX + runId;
    }

    private String versionKey(String runId) {
        return VERSION_KEY_PREFIX + runId;
    }
}
