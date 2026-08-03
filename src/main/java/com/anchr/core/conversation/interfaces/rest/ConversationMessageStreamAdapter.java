package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.AnswerEvent;
import com.anchr.core.conversation.application.AnswerEventBroker;
import com.anchr.core.conversation.application.AnswerIdentity;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.impl.ConversationMessageUseCase;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

/**
 * Spring MVC transport adapter for the synchronous Conversation message use case.
 */
@Slf4j
@Component
public class ConversationMessageStreamAdapter {

    static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    static final String TRACE_PADDING = " ".repeat(2_048);

    private final ConversationMessageUseCase messageUseCase;
    private final Executor streamExecutor;
    private final AgentRuntimeSnapshotService agentRuntimeSnapshotService;
    private final AnswerEventBroker answerEventBroker;
    private final ConversationTurnCodec turnCodec;

    public ConversationMessageStreamAdapter(
            ConversationMessageUseCase messageUseCase,
            @Qualifier("streamEventExecutor") Executor streamExecutor,
            AgentRuntimeSnapshotService agentRuntimeSnapshotService,
            AnswerEventBroker answerEventBroker,
            ConversationTurnCodec turnCodec
    ) {
        this.messageUseCase = messageUseCase;
        this.streamExecutor = streamExecutor;
        this.agentRuntimeSnapshotService = agentRuntimeSnapshotService;
        this.answerEventBroker = answerEventBroker;
        this.turnCodec = turnCodec;
    }

    public SseEmitter stream(
            String sessionId,
            ConversationMessageRequestDTO request
    ) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicReference<String> activeRunId = new AtomicReference<>();
        String channelId = UUID.randomUUID().toString();
        AtomicReference<AnswerIdentity> identityRef = new AtomicReference<>(
                new AnswerIdentity(channelId, null, sessionId, null, null, 1));
        AtomicReference<ConversationMessageResponseDTO> responseRef = new AtomicReference<>();
        AtomicReference<AnswerEventBroker.Subscription> subscriptionRef = new AtomicReference<>();
        Runnable disconnect = () -> {
            clientDisconnected.set(true);
            AnswerEventBroker.Subscription subscription = subscriptionRef.get();
            if (subscription != null) subscription.close();
        };
        emitter.onCompletion(disconnect);
        emitter.onError(error -> disconnect.run());
        emitter.onTimeout(disconnect);
        AnswerEventBroker.Subscription subscription = answerEventBroker.subscribe(channelId, event ->
                sendAnswerEvent(emitter, event, responseRef, clientDisconnected));
        subscriptionRef.set(subscription);
        RequestUserContext context = UserContextHolder.get();
        streamExecutor.execute(() -> {
            UserContextHolder.set(context);
            try {
                ConversationMessageResponseDTO response = messageUseCase.execute(
                        sessionId,
                        request,
                        progressListener(
                                request, emitter, clientDisconnected, activeRunId, identityRef));
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
                }
                AnswerIdentity identity = ensureIdentity(identityRef, channelId, sessionId, response);
                responseRef.set(response);
                answerEventBroker.snapshot(identity, response.getAnswer());
                answerEventBroker.citations(identity, toDomainCitations(response.getCitations()));
                answerEventBroker.completed(identity);
            } catch (SseClientDisconnectedException exception) {
                log.debug("SSE client disconnected, sessionId={}, runId={}",
                        sessionId, activeRunId.get());
            } catch (BusinessException exception) {
                String errorCode = exception.getError() == null
                        ? ApiError.INTERNAL_ERROR.name() : exception.getError().name();
                sendError(
                        emitter,
                        errorCode,
                        exception.getMessage());
                answerEventBroker.failed(identityRef.get(), errorCode);
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
                    answerEventBroker.failed(identityRef.get(), ApiError.INTERNAL_ERROR.name());
                }
            } finally {
                subscription.close();
                UserContextHolder.clear();
            }
        });
        return emitter;
    }

    private ConversationProgressListener progressListener(
            ConversationMessageRequestDTO request,
            SseEmitter emitter,
            AtomicBoolean clientDisconnected,
            AtomicReference<String> activeRunId,
            AtomicReference<AnswerIdentity> identityRef
    ) {
        return new ConversationProgressListener() {
            @Override
            public void onExecutionStarted(String turnId, String runId) {
                activeRunId.compareAndSet(null, runId);
                AnswerIdentity current = identityRef.get();
                AnswerIdentity identity = new AnswerIdentity(
                        current.channelId(), turnId, current.sessionId(), null, runId, 1);
                identityRef.set(identity);
                answerEventBroker.started(identity);
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
                return true;
            }

            @Override
            public void onAnswerDelta(String delta) {
                if (!StringUtils.hasText(delta)) return;
                answerEventBroker.delta(identityRef.get(), delta);
            }

            @Override
            public void onAnswerReset(String answer) {
                answerEventBroker.snapshot(identityRef.get(), answer);
            }
        };
    }

    private AnswerIdentity ensureIdentity(
            AtomicReference<AnswerIdentity> identityRef,
            String channelId,
            String sessionId,
            ConversationMessageResponseDTO response
    ) {
        AnswerIdentity current = identityRef.get();
        if (StringUtils.hasText(current.answerId())) return current;
        AnswerIdentity resolved = new AnswerIdentity(
                channelId, response.getTurnId(), sessionId, null, response.getAgentRunId(), 1);
        identityRef.set(resolved);
        answerEventBroker.started(resolved);
        return resolved;
    }

    private void sendAnswerEvent(
            SseEmitter emitter,
            AnswerEvent event,
            AtomicReference<ConversationMessageResponseDTO> responseRef,
            AtomicBoolean clientDisconnected
    ) {
        if (clientDisconnected.get()) return;
        try {
            switch (event.type()) {
                case DELTA -> sendEvent(emitter, "delta", answerPayload(event));
                case SNAPSHOT -> sendEvent(emitter, "answer_reset", answerPayload(event));
                case CITATIONS -> sendEvent(
                        emitter, "citations", turnCodec.toCitationDTOs(event.citations()));
                case COMPLETED -> {
                    ConversationMessageResponseDTO response = responseRef.get();
                    if (response != null) sendEvent(emitter, "done", doneEvent(response));
                    emitter.complete();
                }
                case FAILED, CANCELLED -> emitter.complete();
                case STARTED, PROGRESS -> {
                    // Trace/progress keeps its existing transport contract.
                }
            }
        } catch (IOException | IllegalStateException exception) {
            clientDisconnected.set(true);
        }
    }

    private Map<String, Object> answerPayload(AnswerEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(event.identity().answerId())) {
            payload.put("answerId", event.identity().answerId());
        }
        payload.put("revision", event.identity().revision());
        payload.put("sequence", event.sequence());
        payload.put("text", event.text() == null ? "" : event.text());
        return payload;
    }

    private List<ConversationCitation> toDomainCitations(
            List<ConversationTurnDTO.CitationDTO> groups
    ) {
        if (groups == null || groups.isEmpty()) return List.of();
        List<ConversationCitation> citations = new ArrayList<>();
        for (ConversationTurnDTO.CitationDTO group : groups) {
            if (group == null || group.getChunks() == null) continue;
            for (ConversationTurnDTO.CitationChunkDTO chunk : group.getChunks()) {
                if (chunk == null) continue;
                ConversationCitation citation = new ConversationCitation();
                citation.setAssetCitationIndex(group.getCitationIndex());
                citation.setFileName(group.getFileName());
                citation.setKbId(group.getKbId());
                citation.setAssetId(group.getAssetId());
                citation.setSegmentId(chunk.getSegmentId());
                citation.setSegmentCitationIndex(chunk.getSegmentIndex());
                citation.setPageNo(chunk.getPageNo());
                citation.setChunkOrder(chunk.getChunkOrder());
                citation.setTitle(chunk.getTitle());
                citation.setContent(chunk.getContent());
                citation.setSnippet(chunk.getSnippet());
                citation.setHitType(chunk.getHitType());
                citation.setAnchor(chunk.getAnchor());
                citation.setWhy(chunk.getWhy());
                citations.add(citation);
            }
        }
        return List.copyOf(citations);
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
