package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.application.agent.AgentRunFinalizer;
import com.anchr.core.conversation.application.agent.AgentRunCancellationRegistry;
import com.anchr.core.conversation.application.agent.AgentConversationCleanupService;
import com.anchr.core.conversation.application.agent.AgentTaskProcessor;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationIntentSource;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.AgentTaskStatus;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationIntentDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.anchr.core.search.application.KbScopeResolver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default conversation application service.
 */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_TURN_LIMIT = 20;
    private static final int MAX_TURN_LIMIT = 100;
    private static final int DEFAULT_SESSION_LIST_LIMIT = 20;
    private static final int MAX_SESSION_LIST_LIMIT = 50;
    private static final int AUTO_TITLE_MAX_LENGTH = 128;
    private static final int LAST_MESSAGE_PREVIEW_MAX_LENGTH = 80;
    private static final String SINGLE_USER_ID = "single_user";

    private final ConversationRepository conversationRepository;
    private final ConversationMessageOrchestrator conversationMessageOrchestrator;
    private final ConversationTurnCodec conversationTurnCodec;
    private final ConversationRetrievalTraceBuilder conversationRetrievalTraceBuilder;
    private final KbScopeResolver kbScopeResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ActivityEventService activityEventService;
    private final AgentTaskRepository agentTaskRepository;
    private final TransactionTemplate transactionTemplate;
    @Qualifier("streamEventExecutor")
    private final Executor streamExecutor;
    @Autowired(required = false)
    private AgentRunFinalizer agentRunFinalizer;
    @Autowired(required = false)
    private AgentTaskProcessor agentTaskProcessor;
    @Autowired(required = false)
    private AgentConversationCleanupService agentConversationCleanupService;
    @Autowired(required = false)
    private AgentRunCancellationRegistry agentRunCancellationRegistry;

    /** Compatibility constructor for legacy unit tests that exercise only the traditional path. */
    public ConversationServiceImpl(ConversationRepository conversationRepository,
                                   ConversationMessageOrchestrator conversationMessageOrchestrator,
                                   ConversationTurnCodec conversationTurnCodec,
                                   ConversationRetrievalTraceBuilder conversationRetrievalTraceBuilder,
                                   KbScopeResolver kbScopeResolver,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry,
                                   ActivityEventService activityEventService,
                                   Executor streamExecutor) {
        this(conversationRepository, conversationMessageOrchestrator, conversationTurnCodec,
                conversationRetrievalTraceBuilder, kbScopeResolver, objectMapper, meterRegistry,
                activityEventService, null, null, streamExecutor);
    }

    @Override
    public ConversationSessionDTO createSession(ConversationCreateRequestDTO request) {
        long now = System.currentTimeMillis();
        String title = safeTrim(request.getTitle());
        ConversationSession session = ConversationSession.createActive(
                newSessionId(),
                SINGLE_USER_ID,
                title,
                now
        );
        session.setKbScope(kbScopeResolver.resolveVisibleKbIds(request.getKbIds()));
        conversationRepository.saveSession(session);
        meterRegistry.counter("conversation.created.count").increment();
        return toSessionDto(session);
    }

    @Override
    public ConversationSessionDTO getSession(String sessionId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        return toSessionDto(session);
    }

    @Override
    public ConversationSessionListDTO listSessions(Integer limit, String cursor) {
        int boundedLimit = normalizeSessionListLimit(limit);
        int offset = decodeSessionListCursor(cursor);
        List<ConversationSession> sessions = conversationRepository.findRecentSessions(
                SINGLE_USER_ID,
                offset + boundedLimit + 1
        );
        List<ConversationSession> page = sessions.stream()
                .skip(offset)
                .limit(boundedLimit)
                .toList();

        ConversationSessionListDTO response = new ConversationSessionListDTO();
        response.setItems(page.stream().map(this::toSessionListItemDto).toList());
        if (sessions.size() > offset + boundedLimit) {
            response.setNextCursor(encodeSessionListCursor(offset + boundedLimit));
        }
        return response;
    }

    @Override
    public ConversationSessionDTO renameSession(String sessionId, ConversationRenameRequestDTO request) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        long now = System.currentTimeMillis();
        session.setTitle(request.getTitle().trim());
        session.touch(now);
        conversationRepository.saveSession(session);
        return toSessionDto(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        loadSessionOrThrow(sessionId);
        if (agentConversationCleanupService != null) {
            agentConversationCleanupService.cancelRunning(sessionId);
        }
        conversationRepository.deleteSession(sessionId);
        if (agentConversationCleanupService != null) {
            agentConversationCleanupService.deleteRecords(sessionId);
        }
        activityEventService.deleteBySessionId(sessionId);
    }

    @Override
    public ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request) {
        return createMessageInternal(sessionId, request, ConversationProgressListener.NOOP);
    }

    private ConversationMessageResponseDTO createMessageInternal(String sessionId,
                                                                  ConversationMessageRequestDTO request,
                                                                  ConversationProgressListener progressListener) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        boolean autoGenerateTitle = shouldAutoGenerateTitle(session.getSessionId(), session.getTitle());
        long now = System.currentTimeMillis();
        applyConversationScope(session, request);
        AnswerMode answerMode = resolveAnswerMode(request);
        request.setAnswerMode(answerMode.name());
        request.setPreferredModalities(resolveRequestedModalities(request.getPreferredModalities()));
        meterRegistry.counter("conversation.active.count").increment();
        String turnId = newTurnId();
        String runId = newRunId();
        ConversationExecutionResult executionResult = conversationMessageOrchestrator.execute(
                session.getSessionId(), turnId, runId, request, progressListener);
        AnswerStatus answerStatus = executionResult.answerStatus();

        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(turnId);
        turn.setSessionId(session.getSessionId());
        turn.setQuery(request.getQuery().trim());
        turn.setRewrittenQuery(executionResult.rewrittenQuery());
        turn.setAnswer(executionResult.answer());
        turn.setKbScopeJson(conversationTurnCodec.serializeKbScope(request.getKbIds()));
        turn.setAssetScopeJson(conversationTurnCodec.serializeAssetScope(request.getAssetIdList()));
        turn.setAnswerMode(answerMode.name());
        turn.setAnswerStatus(answerStatus.name());
        turn.setAnswerFallbackReason(executionResult.fallbackReason());
        applyIntent(turn, executionResult.intent());
        turn.setCitationsJson(conversationTurnCodec.serializeCitations(executionResult.citations()));
        turn.setResultCardsJson(conversationTurnCodec.serializeResultCards(executionResult.resultCards()));
        turn.setRetrievalTraceJson(buildRetrievalTraceJson(request, executionResult));
        turn.setAgentRunId(executionResult.agentRunId());
        turn.setWorkflowVersion(executionResult.workflowVersion());
        turn.setExecutionMode(executionResult.executionMode().name());
        turn.setAgentTaskId(executionResult.agentTask() == null ? null : executionResult.agentTask().taskId());
        turn.setCreatedAt(now);
        try {
            Runnable persistence = () -> {
                if (!conversationRepository.lockActiveSession(session.getSessionId())) {
                    throw new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND);
                }
                conversationRepository.saveTurn(turn);
                if (executionResult.agentTask() != null) {
                    if (agentTaskRepository == null) throw new IllegalStateException("Agent task repository is unavailable");
                    agentTaskRepository.save(newAgentTask(executionResult, turn, now));
                    submitTaskAfterCommit(executionResult.agentTask().taskId());
                }
            };
            if (transactionTemplate == null) persistence.run();
            else transactionTemplate.executeWithoutResult(status -> persistence.run());
        } catch (RuntimeException e) {
            markAgentRunTurnFailed(executionResult.agentRunId(), e);
            throw e;
        }
        if (agentRunFinalizer != null) {
            agentRunFinalizer.markTurnSaved(executionResult.agentRunId());
        }
        if (executionResult.intent() != null && executionResult.intent().type() == ConversationIntentType.KB_QUERY) {
            activityEventService.recordQuestionAsked(
                    session.getSessionId(),
                    turn.getTurnId(),
                    turn.getQuery(),
                    request.getKbIds());
        }
        meterRegistry.counter("conversation.turn.count").increment();

        if (autoGenerateTitle) {
            session.setTitle(buildAutoTitle(request.getQuery(), executionResult.rewrittenQuery()));
        }
        session.touch(now);
        conversationRepository.saveSession(session);

        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId(session.getSessionId());
        response.setTurnId(turn.getTurnId());
        response.setTitle(session.getTitle());
        response.setAgentRunId(turn.getAgentRunId());
        response.setWorkflowVersion(turn.getWorkflowVersion());
        response.setExecutionMode(turn.getExecutionMode());
        response.setAgentTask(toAgentTaskDto(turn.getAgentTaskId()));
        response.setRewrittenQuery(executionResult.rewrittenQuery());
        response.setAnswer(turn.getAnswer());
        response.setKbScope(request.getKbIds());
        response.setAssetScope(request.getAssetIdList());
        response.setAnswerMode(turn.getAnswerMode());
        response.setAnswerStatus(turn.getAnswerStatus());
        response.setAnswerFallbackReason(turn.getAnswerFallbackReason());
        response.setRetrievalStage(executionResult.retrievalExecuted() ? "ANSWERED" : "SKIPPED");
        response.setIntent(toIntentDto(executionResult.intent()));
        response.setCitations(conversationTurnCodec.toCitationDTOs(executionResult.citations()));
        response.setResultCards(executionResult.resultCards());
        response.setRetrievalTrace(buildRetrievalTraceDto(request, executionResult));
        response.setCreatedAt(now);
        if (executionResult.retrievalExecuted()) {
            meterRegistry.summary("answer.citation.count").record(executionResult.citations().size());
            if (executionResult.citations().isEmpty()) {
                meterRegistry.counter("answer.citation.empty.count").increment();
            }
        }
        return response;
    }

    @Override
    public SseEmitter streamMessage(String sessionId, ConversationMessageRequestDTO request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicReference<String> activeRunId = new AtomicReference<>();
        emitter.onError(error -> {
            clientDisconnected.set(true);
            cancelDisconnectedRun(activeRunId.get());
        });
        emitter.onTimeout(() -> {
            clientDisconnected.set(true);
            cancelDisconnectedRun(activeRunId.get());
        });
        RequestUserContext context = UserContextHolder.get();
        streamExecutor.execute(() -> {
            UserContextHolder.set(context);
            try {
                sendEvent(emitter, "trace", Map.of("stage",
                        Boolean.TRUE.equals(request.getAgentEnabled()) ? "agent_thinking" : "routing", "message", "started"));
                ConversationMessageResponseDTO response = createMessageInternal(sessionId, request,
                        new ConversationProgressListener() {
                            @Override
                            public void onRoutingCompleted(ConversationIntentResult intent) {
                                Map<String, Object> trace = new LinkedHashMap<>();
                                trace.put("stage", "routing");
                                trace.put("message", "completed");
                                trace.put("intentType", intent.type().name());
                                trace.put("confidence", intent.confidence());
                                sendProgressEvent(emitter, trace);
                            }

                            @Override
                            public void onStageStarted(String stage) {
                                sendProgressEvent(emitter, Map.of("stage", stage, "message", "started"));
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
                                try {
                                    sendProgressEvent(emitter, trace);
                                } catch (SseClientDisconnectedException e) {
                                    clientDisconnected.set(true);
                                    cancelDisconnectedRun(event.runId());
                                    throw e;
                                }
                            }
                        });
                if (clientDisconnected.get()) {
                    log.debug("SSE client disconnected after cancelling Agent run, sessionId={}, runId={}",
                            sessionId, activeRunId.get());
                    return;
                }
                streamAnswer(emitter, response.getAnswer());
                sendEvent(emitter, "citations", response.getCitations() == null ? List.of() : response.getCitations());
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("turnId", response.getTurnId());
                done.put("title", response.getTitle());
                done.put("kbScope", response.getKbScope());
                done.put("assetScope", response.getAssetScope() == null ? List.of() : response.getAssetScope());
                done.put("answerMode", response.getAnswerMode());
                done.put("answerStatus", response.getAnswerStatus());
                done.put("fallbackReason", response.getAnswerFallbackReason());
                done.put("citationCount", response.getCitations() == null ? 0 : response.getCitations().size());
                done.put("retrievalExecuted", !"SKIPPED".equals(response.getRetrievalStage()));
                if (StringUtils.hasText(response.getAgentRunId())) {
                    done.put("runId", response.getAgentRunId());
                    done.put("workflowVersion", response.getWorkflowVersion());
                }
                done.put("executionMode", response.getExecutionMode());
                if (response.getAgentTask() != null) done.put("agentTask", response.getAgentTask());
                if (response.getIntent() != null) {
                    done.put("intentType", response.getIntent().getType());
                }
                sendEvent(emitter, "done", done);
                emitter.complete();
            } catch (SseClientDisconnectedException e) {
                log.debug("SSE client disconnected, sessionId={}, runId={}", sessionId, activeRunId.get());
            } catch (BusinessException e) {
                sendError(emitter, e.getError() == null ? ApiError.INTERNAL_ERROR.name() : e.getError().name(), e.getMessage());
            } catch (Exception e) {
                if (isClientDisconnect(e)) {
                    clientDisconnected.set(true);
                    cancelDisconnectedRun(activeRunId.get());
                    log.debug("SSE client disconnected, sessionId={}, runId={}", sessionId, activeRunId.get());
                } else {
                    sendError(emitter, ApiError.INTERNAL_ERROR.name(), ApiError.INTERNAL_ERROR.getMessage());
                }
            } finally {
                UserContextHolder.clear();
            }
        });
        return emitter;
    }

    @Override
    public ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        int boundedLimit = normalizeLimit(limit);
        List<ConversationTurn> candidates = conversationRepository.findRecentTurns(session.getSessionId(), MAX_TURN_LIMIT);
        if (candidates.isEmpty()) {
            ConversationTurnListDTO empty = new ConversationTurnListDTO();
            empty.setSessionId(session.getSessionId());
            return empty;
        }

        List<ConversationTurn> filteredTurns = filterBeforeTurn(candidates, session.getSessionId(), beforeTurnId);
        filteredTurns.sort(Comparator.comparingLong(ConversationTurn::getCreatedAt));
        if (filteredTurns.size() > boundedLimit) {
            filteredTurns = filteredTurns.subList(filteredTurns.size() - boundedLimit, filteredTurns.size());
        }

        ConversationTurnListDTO response = new ConversationTurnListDTO();
        response.setSessionId(session.getSessionId());
        response.setTurns(filteredTurns.stream().map(this::toTurnDto).toList());
        return response;
    }

    private List<ConversationTurn> filterBeforeTurn(List<ConversationTurn> candidates, String sessionId, String beforeTurnId) {
        if (!StringUtils.hasText(beforeTurnId)) {
            return new ArrayList<>(candidates);
        }
        ConversationTurn beforeTurn = conversationRepository.findTurn(sessionId, beforeTurnId)
                .orElseThrow(() -> new IllegalArgumentException("beforeTurnId is invalid"));
        long boundary = beforeTurn.getCreatedAt();
        return candidates.stream()
                .filter(turn -> turn.getCreatedAt() < boundary)
                .toList();
    }

    private ConversationSession loadSessionOrThrow(String sessionId) {
        return conversationRepository.findSession(sessionId)
                .orElseThrow(() -> new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));
    }

    private ConversationSessionDTO toSessionDto(ConversationSession session) {
        ConversationSessionDTO dto = new ConversationSessionDTO();
        dto.setSessionId(session.getSessionId());
        dto.setUserId(session.getUserId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus().name());
        dto.setKbScope(session.getKbScope() == null ? List.of() : session.getKbScope());
        dto.setAssetScope(session.getAssetScope() == null ? List.of() : session.getAssetScope());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        dto.setExpiresAt(session.getExpiresAt());
        return dto;
    }

    private ConversationSessionDTO toSessionListItemDto(ConversationSession session) {
        ConversationSessionDTO dto = toSessionDto(session);
        dto.setLastMessagePreview(resolveLastMessagePreview(session.getSessionId()));
        return dto;
    }

    private String resolveLastMessagePreview(String sessionId) {
        List<ConversationTurn> turns = conversationRepository.findRecentTurns(sessionId, 1);
        if (turns.isEmpty()) {
            return null;
        }
        ConversationTurn latest = turns.getFirst();
        String preview = StringUtils.hasText(latest.getAnswer()) ? latest.getAnswer() : latest.getQuery();
        if (!StringUtils.hasText(preview)) {
            return null;
        }
        String normalized = preview.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= LAST_MESSAGE_PREVIEW_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, LAST_MESSAGE_PREVIEW_MAX_LENGTH);
    }

    private ConversationTurnDTO toTurnDto(ConversationTurn turn) {
        ConversationTurnDTO dto = new ConversationTurnDTO();
        dto.setTurnId(turn.getTurnId());
        dto.setSessionId(turn.getSessionId());
        dto.setAgentRunId(turn.getAgentRunId());
        dto.setWorkflowVersion(turn.getWorkflowVersion());
        dto.setExecutionMode(StringUtils.hasText(turn.getExecutionMode()) ? turn.getExecutionMode() : "TRADITIONAL");
        dto.setAgentTask(toAgentTaskDto(turn.getAgentTaskId()));
        dto.setQuery(turn.getQuery());
        dto.setRewrittenQuery(turn.getRewrittenQuery());
        dto.setAnswer(turn.getAnswer());
        dto.setKbScope(conversationTurnCodec.parseKbScope(turn.getKbScopeJson()));
        dto.setAssetScope(conversationTurnCodec.parseAssetScope(turn.getAssetScopeJson()));
        dto.setAnswerMode(turn.getAnswerMode());
        dto.setAnswerStatus(resolveTurnAnswerStatus(turn).name());
        dto.setAnswerFallbackReason(resolveTurnFallbackReason(turn));
        dto.setIntent(toIntentDto(turn));
        dto.setCitations(conversationTurnCodec.parseCitations(turn.getCitationsJson()));
        dto.setResultCards(conversationTurnCodec.parseResultCards(turn.getResultCardsJson()));
        dto.setCreatedAt(turn.getCreatedAt());
        return dto;
    }

    private AnswerStatus resolveTurnAnswerStatus(ConversationTurn turn) {
        if (StringUtils.hasText(turn.getAnswerStatus())) {
            try {
                return AnswerStatus.valueOf(turn.getAnswerStatus().trim());
            } catch (IllegalArgumentException ignored) {
                // Fall through to legacy trace inference.
            }
        }
        Map<?, ?> trace = parseRetrievalTrace(turn.getRetrievalTraceJson());
        Object fallback = trace.get("answerFallback");
        if (!Boolean.TRUE.equals(fallback)) {
            return AnswerStatus.ANSWERED;
        }
        String reason = trace.get("answerFallbackReason") instanceof String value ? value : null;
        return StringUtils.hasText(reason) && reason.startsWith("no_evidence")
                ? AnswerStatus.NO_EVIDENCE
                : AnswerStatus.MODEL_FALLBACK;
    }

    private String resolveTurnFallbackReason(ConversationTurn turn) {
        if (StringUtils.hasText(turn.getAnswerFallbackReason())) {
            return turn.getAnswerFallbackReason();
        }
        Object reason = parseRetrievalTrace(turn.getRetrievalTraceJson()).get("answerFallbackReason");
        return reason instanceof String value && StringUtils.hasText(value) ? value : null;
    }

    private void applyIntent(ConversationTurn turn, ConversationIntentResult intent) {
        if (intent == null) {
            turn.setIntentType(null); turn.setIntentConfidence(null); turn.setIntentReason(null);
            turn.setIntentSource(null); turn.setIntentFallback(false); return;
        }
        turn.setIntentType(intent.type().name());
        turn.setIntentConfidence(intent.confidence());
        turn.setIntentReason(truncate(intent.reason(), 255));
        turn.setIntentSource(intent.source().name());
        turn.setIntentFallback(intent.fallbackUsed());
    }

    private ConversationIntentDTO toIntentDto(ConversationIntentResult intent) {
        if (intent == null) return null;
        ConversationIntentDTO dto = new ConversationIntentDTO();
        dto.setType(intent.type().name());
        dto.setConfidence(intent.confidence());
        dto.setReason(intent.reason());
        dto.setSource(intent.source().name());
        dto.setFallbackUsed(intent.fallbackUsed());
        dto.setRetrievalRequired(intent.retrievalRequired());
        return dto;
    }

    private ConversationIntentDTO toIntentDto(ConversationTurn turn) {
        if ("AGENT".equals(turn.getExecutionMode()) && !StringUtils.hasText(turn.getIntentType())) return null;
        ConversationIntentType type;
        ConversationIntentSource source;
        try {
            type = StringUtils.hasText(turn.getIntentType())
                    ? ConversationIntentType.valueOf(turn.getIntentType()) : ConversationIntentType.KB_QUERY;
        } catch (IllegalArgumentException e) {
            type = ConversationIntentType.KB_QUERY;
        }
        try {
            source = StringUtils.hasText(turn.getIntentSource())
                    ? ConversationIntentSource.valueOf(turn.getIntentSource()) : ConversationIntentSource.LEGACY;
        } catch (IllegalArgumentException e) {
            source = ConversationIntentSource.LEGACY;
        }
        return toIntentDto(new ConversationIntentResult(type,
                turn.getIntentConfidence() == null ? 0.0D : turn.getIntentConfidence(),
                turn.getIntentReason(), source, turn.isIntentFallback()));
    }

    private AgentTask newAgentTask(ConversationExecutionResult result, ConversationTurn turn, long now) {
        AgentTask task = new AgentTask();
        task.setTaskId(result.agentTask().taskId()); task.setRunId(result.agentRunId()); task.setTurnId(turn.getTurnId());
        task.setSessionId(turn.getSessionId()); task.setUserId(SINGLE_USER_ID); task.setTaskType(result.agentTask().type());
        task.setStatus(AgentTaskStatus.PENDING.name()); task.setProgress(0); task.setCurrentStage("QUEUED");
        task.setRequestJson(result.agentTask().requestJson()); task.setCitationsJson("[]"); task.setCreatedAt(now); task.setUpdatedAt(now);
        return task;
    }

    private AgentTaskDTO toAgentTaskDto(String taskId) {
        if (!StringUtils.hasText(taskId) || agentTaskRepository == null) return null;
        return agentTaskRepository.findById(taskId).map(task -> {
            AgentTaskDTO dto = new AgentTaskDTO(); dto.setTaskId(task.getTaskId()); dto.setType(task.getTaskType());
            dto.setStatus(task.getStatus()); dto.setProgress(task.getProgress()); dto.setCurrentStage(task.getCurrentStage());
            dto.setAnswer(task.getAnswer()); dto.setCitations(conversationTurnCodec.parseCitations(task.getCitationsJson()));
            dto.setErrorCode(task.getErrorCode()); dto.setErrorMessage(task.getErrorMessage()); return dto;
        }).orElse(null);
    }

    private void submitTaskAfterCommit(String taskId) {
        if (agentTaskProcessor == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { agentTaskProcessor.trigger(taskId); }
            });
        } else {
            agentTaskProcessor.trigger(taskId);
        }
    }

    private String buildRetrievalTraceJson(ConversationMessageRequestDTO request,
                                           ConversationExecutionResult result) {
        ConversationMessagePipelineResult rag = result.ragResult();
        if (rag == null) {
            return "{}";
        }
        return conversationRetrievalTraceBuilder.buildTraceJson(request, rag.rewriteResult(),
                rag.retrievalResult(), rag.answerGenerationResult());
    }

    private ConversationMessageResponseDTO.RetrievalTraceDTO buildRetrievalTraceDto(
            ConversationMessageRequestDTO request, ConversationExecutionResult result) {
        ConversationMessagePipelineResult rag = result.ragResult();
        if (rag == null) {
            return null;
        }
        return conversationRetrievalTraceBuilder.buildTraceDto(request, rag.rewriteResult(),
                rag.retrievalResult(), rag.answerGenerationResult());
    }

    private void sendProgressEvent(SseEmitter emitter, Object data) {
        try {
            sendEvent(emitter, "trace", data);
        } catch (IOException e) {
            throw new SseClientDisconnectedException(e);
        }
    }

    private void cancelDisconnectedRun(String runId) {
        if (agentRunCancellationRegistry != null && StringUtils.hasText(runId)) {
            agentRunCancellationRegistry.cancel(runId);
        }
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SseClientDisconnectedException) return true;
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("responsebodyemitter has already completed")
                        || normalized.contains("async request is not usable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private Map<?, ?> parseRetrievalTrace(String retrievalTraceJson) {
        if (!StringUtils.hasText(retrievalTraceJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(retrievalTraceJson, Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_TURN_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_TURN_LIMIT));
    }

    private int normalizeSessionListLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SESSION_LIST_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_SESSION_LIST_LIMIT));
    }

    private String encodeSessionListCursor(int offset) {
        String payload = "{\"offset\":" + Math.max(0, offset) + "}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private int decodeSessionListCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.trim());
            Map<?, ?> payload = objectMapper.readValue(decoded, Map.class);
            Object offset = payload.get("offset");
            if (offset instanceof Number number) {
                return Math.max(0, number.intValue());
            }
            return 0;
        } catch (Exception e) {
            throw new IllegalArgumentException("cursor is invalid");
        }
    }

    private String safeTrim(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }

    private void applyConversationScope(ConversationSession session, ConversationMessageRequestDTO request) {
        List<String> requested = request.getKbIds();
        if (CollectionUtils.isEmpty(requested) && !CollectionUtils.isEmpty(session.getKbScope())) {
            request.setKbIds(session.getKbScope());
        } else {
            request.setKbIds(kbScopeResolver.resolveVisibleKbIds(requested));
        }
    }

    private AnswerMode resolveAnswerMode(ConversationMessageRequestDTO request) {
        return AnswerMode.from(request.getAnswerMode());
    }

    private List<String> resolveRequestedModalities(List<String> requestedModalities) {
        if (requestedModalities == null || requestedModalities.isEmpty()) {
            return List.of("MIXED");
        }
        List<String> normalized = requestedModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of("MIXED") : normalized;
    }

    private void streamAnswer(SseEmitter emitter, String answer) throws IOException {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        int chunkSize = 48;
        for (int start = 0; start < answer.length(); start += chunkSize) {
            int end = Math.min(answer.length(), start + chunkSize);
            sendEvent(emitter, "delta", Map.of("text", answer.substring(start, end)));
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        try {
            sendEvent(emitter, "error", Map.of("code", code, "message", message));
            emitter.complete();
        } catch (IOException e) {
            if (isClientDisconnect(e)) return;
            try {
                emitter.completeWithError(e);
            } catch (Exception completeError) {
                log.warn("failed to complete SSE emitter after error event failure, code={}", code, completeError);
            }
        } catch (Exception e) {
            if (isClientDisconnect(e)) return;
            log.warn("failed to send SSE error event, code={}", code, e);
            try {
                emitter.completeWithError(e);
            } catch (Exception completeError) {
                log.warn("failed to complete SSE emitter after runtime error, code={}", code, completeError);
            }
        }
    }

    private static class SseClientDisconnectedException extends RuntimeException {
        private SseClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }

    private boolean shouldAutoGenerateTitle(String sessionId, String existingTitle) {
        if (StringUtils.hasText(existingTitle)) {
            return false;
        }
        return conversationRepository.findRecentTurns(sessionId, 1).isEmpty();
    }

    private String buildAutoTitle(String query, String rewrittenQuery) {
        String candidate = StringUtils.hasText(rewrittenQuery) ? rewrittenQuery : query;
        if (!StringUtils.hasText(candidate)) {
            return "新会话";
        }
        String normalized = candidate.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= AUTO_TITLE_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, AUTO_TITLE_MAX_LENGTH);
    }

    private String newSessionId() {
        return "cvs_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newTurnId() {
        return "turn_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void markAgentRunTurnFailed(String runId, RuntimeException original) {
        if (agentRunFinalizer == null) return;
        try {
            agentRunFinalizer.markTurnFailed(runId);
        } catch (RuntimeException traceError) {
            original.addSuppressed(traceError);
        }
    }
}
