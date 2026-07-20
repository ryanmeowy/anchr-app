package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Publishes background-task progress and canonical terminal answers to connected Ask clients. */
@Slf4j
@Component
public class AgentTaskStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 11 * 60_000L;
    private static final String STREAM_PADDING = " ".repeat(2_048);

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, String> latestAnswers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(AgentTaskDTO snapshot) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        String taskId = snapshot.getTaskId();
        CopyOnWriteArrayList<SseEmitter> taskSubscribers = subscribers.computeIfAbsent(
                taskId, ignored -> new CopyOnWriteArrayList<>());
        taskSubscribers.add(emitter);
        Runnable remove = () -> remove(taskId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        try {
            send(emitter, "task", snapshot);
            String latestAnswer = latestAnswers.get(taskId);
            if (latestAnswer != null && !latestAnswer.equals(snapshot.getAnswer())) {
                send(emitter, "answer_reset", Map.of("text", latestAnswer));
            }
            if (terminal(snapshot.getStatus())) {
                send(emitter, "done", Map.of("taskId", taskId));
                emitter.complete();
            }
        } catch (IOException e) {
            remove.run();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public void publishTask(AgentTask task) {
        if (task == null || !StringUtils.hasText(task.getTaskId())) return;
        publish(task.getTaskId(), "task", taskEvent(task));
    }

    static Map<String, Object> taskEvent(AgentTask task) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", task.getTaskId());
        event.put("type", task.getTaskType());
        event.put("status", task.getStatus());
        event.put("progress", task.getProgress());
        event.put("currentStage", task.getCurrentStage());
        if (StringUtils.hasText(task.getErrorCode())) event.put("errorCode", task.getErrorCode());
        if (StringUtils.hasText(task.getErrorMessage())) event.put("errorMessage", task.getErrorMessage());
        return event;
    }

    public void publishDelta(String taskId, String delta) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(delta)) return;
        latestAnswers.compute(taskId, (ignored, current) -> (current == null ? "" : current) + delta);
        publish(taskId, "delta", Map.of("text", delta));
    }

    public void publishReset(String taskId, String answer) {
        if (!StringUtils.hasText(taskId)) return;
        String value = answer == null ? "" : answer;
        latestAnswers.put(taskId, value);
        publish(taskId, "answer_reset", Map.of("text", value));
    }

    public void complete(AgentTask task) {
        if (task == null || !StringUtils.hasText(task.getTaskId())) return;
        publishTask(task);
        if (StringUtils.hasText(task.getAnswer())) {
            publishReset(task.getTaskId(), task.getAnswer());
        }
        complete(task.getTaskId());
    }

    public void complete(AgentTaskDTO task) {
        if (task == null || !StringUtils.hasText(task.getTaskId())) return;
        publish(task.getTaskId(), "task", task);
        complete(task.getTaskId());
    }

    private void complete(String taskId) {
        publish(taskId, "done", Map.of("taskId", taskId));
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.remove(taskId);
        if (emitters != null) {
            emitters.forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // Completion is best effort after the terminal event.
                }
            });
        }
        latestAnswers.remove(taskId);
    }

    private void publish(String taskId, String event, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(taskId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event, data);
            } catch (IOException | IllegalStateException e) {
                remove(taskId, emitter);
                log.debug("Agent task SSE subscriber disconnected, taskId={}", taskId);
            }
        }
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
        if ("task".equals(event)) builder.comment(STREAM_PADDING);
        emitter.send(builder.data(data));
    }

    private void remove(String taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(taskId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) subscribers.remove(taskId, emitters);
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
