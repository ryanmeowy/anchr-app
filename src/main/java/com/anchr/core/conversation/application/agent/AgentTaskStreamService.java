package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AnswerEvent;
import com.anchr.core.conversation.application.AnswerEventBroker;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Converts the shared local AnswerEvent channel into the existing Agent Task SSE protocol. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = 11 * 60_000L;
    private static final String STREAM_PADDING = " ".repeat(2_048);

    private final AnswerEventBroker eventBroker;
    private final ConversationTurnCodec turnCodec;

    public SseEmitter subscribe(AgentTaskDTO snapshot) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        String taskId = snapshot.getTaskId();
        TaskProjection projection = new TaskProjection(snapshot);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<AnswerEventBroker.Subscription> subscriptionRef = new AtomicReference<>();
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) return;
            AnswerEventBroker.Subscription subscription = subscriptionRef.get();
            if (subscription != null) subscription.close();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(ignored -> close.run());
        try {
            send(emitter, "task", snapshot);
            if (terminal(snapshot.getStatus())) {
                sendTerminalSnapshot(emitter, snapshot);
                emitter.complete();
                return emitter;
            }
            AnswerEventBroker.Subscription subscription = eventBroker.subscribe(taskId, event -> {
                if (closed.get()) return;
                try {
                    handle(emitter, projection, event);
                    if (terminal(event.type())) {
                        emitter.complete();
                        close.run();
                    }
                } catch (IOException | IllegalStateException exception) {
                    close.run();
                }
            });
            subscriptionRef.set(subscription);
            if (closed.get()) subscription.close();
        } catch (IOException exception) {
            close.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private void handle(SseEmitter emitter, TaskProjection projection, AnswerEvent event) throws IOException {
        if (!projection.accepts(event)) return;
        switch (event.type()) {
            case STARTED -> send(emitter, "answer_reset", answerPayload(event, ""));
            case PROGRESS -> send(emitter, "task", projection.progress(event));
            case DELTA -> send(emitter, "delta", answerPayload(event, event.text()));
            case SNAPSHOT -> send(emitter, "answer_reset", answerPayload(event, event.text()));
            case CITATIONS -> send(emitter, "citations", citationsPayload(event));
            case COMPLETED, FAILED, CANCELLED -> send(emitter, "done", identityPayload(event));
        }
    }

    private void sendTerminalSnapshot(SseEmitter emitter, AgentTaskDTO task) throws IOException {
        if (StringUtils.hasText(task.getAnswer())) {
            Map<String, Object> answer = terminalIdentity(task);
            answer.put("sequence", 1L);
            answer.put("text", task.getAnswer());
            send(emitter, "answer_reset", answer);
        }
        Map<String, Object> citations = terminalIdentity(task);
        citations.put("citations", task.getCitations() == null ? List.of() : task.getCitations());
        send(emitter, "citations", citations);
        send(emitter, "done", terminalIdentity(task));
    }

    private Map<String, Object> answerPayload(AnswerEvent event, String text) {
        Map<String, Object> payload = identityPayload(event);
        payload.put("sequence", event.sequence());
        payload.put("text", text == null ? "" : text);
        return payload;
    }

    private Map<String, Object> citationsPayload(AnswerEvent event) {
        Map<String, Object> payload = identityPayload(event);
        payload.put("citations", turnCodec.toCitationDTOs(event.citations()));
        return payload;
    }

    private Map<String, Object> identityPayload(AnswerEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(event.identity().taskId())) payload.put("taskId", event.identity().taskId());
        if (StringUtils.hasText(event.identity().answerId())) payload.put("answerId", event.identity().answerId());
        payload.put("revision", event.identity().revision());
        return payload;
    }

    private Map<String, Object> terminalIdentity(AgentTaskDTO task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        if (StringUtils.hasText(task.getTurnId())) payload.put("answerId", task.getTurnId());
        payload.put("revision", Math.max(1, task.getRevision()));
        return payload;
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().comment(STREAM_PADDING).name(event).data(data));
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean terminal(AnswerEvent.Type type) {
        return type == AnswerEvent.Type.COMPLETED
                || type == AnswerEvent.Type.FAILED
                || type == AnswerEvent.Type.CANCELLED;
    }

    private static final class TaskProjection {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private long revision;

        private TaskProjection(AgentTaskDTO task) {
            values.put("taskId", task.getTaskId());
            values.put("sessionId", task.getSessionId());
            values.put("turnId", task.getTurnId());
            values.put("runId", task.getRunId());
            values.put("type", task.getType());
            values.put("status", task.getStatus());
            values.put("progress", task.getProgress());
            values.put("stage", task.getCurrentStage());
            values.put("errorCode", task.getErrorCode());
            values.put("errorMessage", task.getErrorMessage());
            revision = Math.max(1, task.getRevision());
            values.put("revision", revision);
        }

        synchronized boolean accepts(AnswerEvent event) {
            return event.identity().revision() >= revision;
        }

        synchronized Map<String, Object> progress(AnswerEvent event) {
            revision = event.identity().revision();
            values.put("sessionId", event.identity().sessionId());
            values.put("turnId", event.identity().answerId());
            values.put("runId", event.identity().runId());
            values.put("revision", revision);
            values.put("progress", event.progress());
            values.put("stage", event.stage());
            values.put("currentStage", event.stage());
            values.put("status", switch (event.stage() == null ? "" : event.stage()) {
                case "RETRY_WAIT" -> "PENDING";
                case "COMPLETED" -> "SUCCEEDED";
                case "FAILED" -> "FAILED";
                case "CANCELLED" -> "CANCELLED";
                default -> "RUNNING";
            });
            return new LinkedHashMap<>(values);
        }
    }
}
