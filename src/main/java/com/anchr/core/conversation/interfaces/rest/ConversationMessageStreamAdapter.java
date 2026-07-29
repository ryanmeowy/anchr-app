package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.impl.ConversationMessageUseCase;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring MVC transport adapter for the synchronous Conversation message use case.
 */
@Slf4j
@Component
public class ConversationMessageStreamAdapter {

    static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    static final String TRACE_PADDING = " ".repeat(2_048);
    static final int ANSWER_CHUNK_SIZE = 48;

    private final ConversationMessageUseCase messageUseCase;
    private final Executor streamExecutor;
    private final AgentRuntimeSnapshotService agentRuntimeSnapshotService;

    public ConversationMessageStreamAdapter(
            ConversationMessageUseCase messageUseCase,
            @Qualifier("streamEventExecutor") Executor streamExecutor,
            AgentRuntimeSnapshotService agentRuntimeSnapshotService
    ) {
        this.messageUseCase = messageUseCase;
        this.streamExecutor = streamExecutor;
        this.agentRuntimeSnapshotService = agentRuntimeSnapshotService;
    }

    public SseEmitter stream(
            String sessionId,
            ConversationMessageRequestDTO request
    ) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicReference<String> activeRunId = new AtomicReference<>();
        emitter.onError(error -> clientDisconnected.set(true));
        emitter.onTimeout(() -> clientDisconnected.set(true));
        RequestUserContext context = UserContextHolder.get();
        streamExecutor.execute(() -> {
            UserContextHolder.set(context);
            try {
                ConversationMessageResponseDTO response = messageUseCase.execute(
                        sessionId,
                        request,
                        progressListener(
                                request, emitter, clientDisconnected, activeRunId));
                if (StringUtils.hasText(response.getAgentRunId())) {
                    agentRuntimeSnapshotService.publishMessage(
                            response.getAgentRunId(), response);
                }
                if (clientDisconnected.get()) {
                    log.debug(
                            "SSE client disconnected after Agent run persisted, "
                                    + "sessionId={}, runId={}",
                            sessionId,
                            activeRunId.get());
                    return;
                }
                streamAnswer(emitter, response.getAnswer());
                sendEvent(
                        emitter,
                        "citations",
                        response.getCitations() == null
                                ? List.of() : response.getCitations());
                sendEvent(emitter, "done", doneEvent(response));
                emitter.complete();
            } catch (SseClientDisconnectedException exception) {
                log.debug("SSE client disconnected, sessionId={}, runId={}",
                        sessionId, activeRunId.get());
            } catch (BusinessException exception) {
                sendError(
                        emitter,
                        exception.getError() == null
                                ? ApiError.INTERNAL_ERROR.name()
                                : exception.getError().name(),
                        exception.getMessage());
            } catch (Exception exception) {
                if (isClientDisconnect(exception)) {
                    clientDisconnected.set(true);
                    log.debug("SSE client disconnected, sessionId={}, runId={}",
                            sessionId, activeRunId.get());
                } else {
                    sendError(
                            emitter,
                            ApiError.INTERNAL_ERROR.name(),
                            ApiError.INTERNAL_ERROR.getMessage());
                }
            } finally {
                UserContextHolder.clear();
            }
        });
        return emitter;
    }

    private ConversationProgressListener progressListener(
            ConversationMessageRequestDTO request,
            SseEmitter emitter,
            AtomicBoolean clientDisconnected,
            AtomicReference<String> activeRunId
    ) {
        return new ConversationProgressListener() {
            @Override
            public void onExecutionStarted(String turnId, String runId) {
                Map<String, Object> initialTrace = new LinkedHashMap<>();
                initialTrace.put(
                        "stage",
                        Boolean.TRUE.equals(request.getAgentEnabled())
                                ? "agent_thinking" : "routing");
                initialTrace.put(
                        "message",
                        Boolean.TRUE.equals(request.getAgentEnabled())
                                ? "decision_started" : "started");
                initialTrace.put("turnId", turnId);
                if (Boolean.TRUE.equals(request.getAgentEnabled())) {
                    initialTrace.put("runId", runId);
                    initialTrace.put("details", Map.of(
                            "stepOrder", 1,
                            "turnId", turnId,
                            "decision", "ANALYZING"));
                }
                sendProgressEventSafely(
                        emitter, initialTrace, clientDisconnected);
            }

            @Override
            public void onRoutingCompleted(ConversationIntentResult intent) {
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("stage", "routing");
                trace.put("message", "completed");
                trace.put("intentType", intent.type().name());
                trace.put("confidence", intent.confidence());
                sendProgressEventSafely(emitter, trace, clientDisconnected);
            }

            @Override
            public void onStageStarted(String stage) {
                sendProgressEventSafely(
                        emitter,
                        Map.of("stage", stage, "message", "started"),
                        clientDisconnected);
            }

            @Override
            public void onAgentProgress(AgentProgressEvent event) {
                activeRunId.compareAndSet(null, event.runId());
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("stage", event.stage());
                trace.put("message", event.message());
                trace.put("attempt", event.attempt());
                trace.put("runId", event.runId());
                if (event.details() != null && !event.details().isEmpty()) {
                    trace.put("details", event.details());
                }
                sendProgressEventSafely(emitter, trace, clientDisconnected);
                agentRuntimeSnapshotService.publishProgress(event);
            }

            @Override
            public boolean supportsAnswerStreaming() {
                // Only the finalized, citation-normalized answer is safe to expose.
                return false;
            }
        };
    }

    private Map<String, Object> doneEvent(ConversationMessageResponseDTO response) {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("turnId", response.getTurnId());
        done.put("title", response.getTitle());
        done.put("sessionUpdatedAt", response.getSessionUpdatedAt());
        done.put("kbScope", response.getKbScope());
        done.put(
                "assetScope",
                response.getAssetScope() == null ? List.of() : response.getAssetScope());
        done.put("answerMode", response.getAnswerMode());
        done.put("answerStatus", response.getAnswerStatus());
        done.put("fallbackReason", response.getAnswerFallbackReason());
        done.put(
                "citationCount",
                response.getCitations() == null ? 0 : response.getCitations().size());
        done.put(
                "retrievalExecuted",
                !"SKIPPED".equals(response.getRetrievalStage()));
        if (StringUtils.hasText(response.getAgentRunId())) {
            done.put("runId", response.getAgentRunId());
            done.put("workflowVersion", response.getWorkflowVersion());
        }
        done.put("executionMode", response.getExecutionMode());
        if (response.getAgentTask() != null) {
            done.put("agentTask", response.getAgentTask());
        }
        if (response.getIntent() != null) {
            done.put("intentType", response.getIntent().getType());
        }
        return done;
    }

    private void sendProgressEvent(
            SseEmitter emitter,
            Object data
    ) {
        try {
            emitter.send(SseEmitter.event()
                    .name("trace")
                    .comment(TRACE_PADDING)
                    .data(data));
        } catch (IOException exception) {
            throw new SseClientDisconnectedException(exception);
        }
    }

    private void sendProgressEventSafely(
            SseEmitter emitter,
            Object data,
            AtomicBoolean clientDisconnected
    ) {
        if (clientDisconnected.get()) {
            return;
        }
        try {
            sendProgressEvent(emitter, data);
        } catch (SseClientDisconnectedException exception) {
            clientDisconnected.set(true);
        }
    }

    private void streamAnswer(SseEmitter emitter, String answer) throws IOException {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        for (int start = 0; start < answer.length(); start += ANSWER_CHUNK_SIZE) {
            int end = Math.min(answer.length(), start + ANSWER_CHUNK_SIZE);
            sendEvent(
                    emitter,
                    "delta",
                    Map.of("text", answer.substring(start, end)));
        }
    }

    private void sendEvent(
            SseEmitter emitter,
            String event,
            Object data
    ) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        try {
            sendEvent(
                    emitter,
                    "error",
                    Map.of("code", code, "message", message));
            emitter.complete();
        } catch (IOException exception) {
            if (isClientDisconnect(exception)) {
                return;
            }
            try {
                emitter.completeWithError(exception);
            } catch (Exception completeError) {
                log.warn(
                        "failed to complete SSE emitter after error event failure, code={}",
                        code,
                        completeError);
            }
        } catch (Exception exception) {
            if (isClientDisconnect(exception)) {
                return;
            }
            log.warn("failed to send SSE error event, code={}", code, exception);
            try {
                emitter.completeWithError(exception);
            } catch (Exception completeError) {
                log.warn(
                        "failed to complete SSE emitter after runtime error, code={}",
                        code,
                        completeError);
            }
        }
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SseClientDisconnectedException) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains(
                        "responsebodyemitter has already completed")
                        || normalized.contains("async request is not usable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class SseClientDisconnectedException
            extends RuntimeException {

        private SseClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
