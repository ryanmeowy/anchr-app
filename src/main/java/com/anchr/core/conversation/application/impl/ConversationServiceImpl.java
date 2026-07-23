package com.anchr.core.conversation.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.application.agent.AgentConversationCleanupService;
import com.anchr.core.conversation.application.agent.AgentRunFinalizer;
import com.anchr.core.conversation.application.agent.AgentTaskProcessor;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationExecutionMode;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationIntentSource;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.AgentTaskStatus;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationSessionPosition;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationTurnPosition;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationIntentDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.search.application.KbScopeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default conversation application service.
 */
@Service
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_TURN_LIMIT = 20;
    private static final int MAX_TURN_LIMIT = 100;
    private static final int DEFAULT_SESSION_LIST_LIMIT = 20;
    private static final int MAX_SESSION_LIST_LIMIT = 50;
    private static final int SESSION_LIST_CURSOR_VERSION = 1;
    private static final int MAX_SESSION_LIST_CURSOR_LENGTH = 1_024;
    private static final long MAX_SESSION_CURSOR_UPDATED_AT = LocalDateTime.of(
                    9999, 12, 31, 23, 59, 59, 999_000_000)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
    private static final int AUTO_TITLE_MAX_LENGTH = 128;
    private static final String SSE_TRACE_PADDING = " ".repeat(2_048);
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
    private final Executor streamExecutor;

    private final AgentRunFinalizer agentRunFinalizer;
    private final AgentTaskProcessor agentTaskProcessor;
    private final AgentConversationCleanupService agentConversationCleanupService;
    private final AgentRuntimeSnapshotService agentRuntimeSnapshotService;

    @Autowired
    public ConversationServiceImpl(ConversationRepository conversationRepository,
                                   ConversationMessageOrchestrator conversationMessageOrchestrator,
                                   ConversationTurnCodec conversationTurnCodec,
                                   ConversationRetrievalTraceBuilder conversationRetrievalTraceBuilder,
                                   KbScopeResolver kbScopeResolver,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry,
                                   ActivityEventService activityEventService,
                                   AgentTaskRepository agentTaskRepository,
                                   TransactionTemplate transactionTemplate,
                                   @Qualifier("streamEventExecutor") Executor streamExecutor,
                                   AgentRunFinalizer agentRunFinalizer,
                                   AgentTaskProcessor agentTaskProcessor,
                                   AgentConversationCleanupService agentConversationCleanupService,
                                   AgentRuntimeSnapshotService agentRuntimeSnapshotService) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.conversationMessageOrchestrator =
                Objects.requireNonNull(conversationMessageOrchestrator);
        this.conversationTurnCodec = Objects.requireNonNull(conversationTurnCodec);
        this.conversationRetrievalTraceBuilder =
                Objects.requireNonNull(conversationRetrievalTraceBuilder);
        this.kbScopeResolver = Objects.requireNonNull(kbScopeResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.activityEventService = Objects.requireNonNull(activityEventService);
        this.agentTaskRepository = Objects.requireNonNull(agentTaskRepository);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.streamExecutor = Objects.requireNonNull(streamExecutor);
        this.agentRunFinalizer = Objects.requireNonNull(agentRunFinalizer);
        this.agentTaskProcessor = Objects.requireNonNull(agentTaskProcessor);
        this.agentConversationCleanupService =
                Objects.requireNonNull(agentConversationCleanupService);
        this.agentRuntimeSnapshotService =
                Objects.requireNonNull(agentRuntimeSnapshotService);
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
        conversationRepository.createSession(session);
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
        ConversationSessionPosition before = decodeSessionListCursor(cursor);
        List<ConversationSession> candidates = conversationRepository.findSessionPage(
                SINGLE_USER_ID,
                before,
                boundedLimit + 1
        );
        boolean hasMore = candidates.size() > boundedLimit;
        List<ConversationSession> page = candidates.stream()
                .limit(boundedLimit)
                .toList();

        ConversationSessionListDTO response = new ConversationSessionListDTO();
        response.setItems(page.stream().map(this::toSessionDto).toList());
        if (hasMore && !page.isEmpty()) {
            ConversationSession last = page.getLast();
            response.setNextCursor(encodeSessionListCursor(
                    new ConversationSessionPosition(last.getSessionId(), last.getUpdatedAt())));
        }
        return response;
    }

    @Override
    public ConversationSessionDTO renameSession(String sessionId, ConversationRenameRequestDTO request) {
        long now = System.currentTimeMillis();
        conversationRepository.renameSession(sessionId, request.getTitle().trim(), now);
        return toSessionDto(loadSessionOrThrow(sessionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        loadSessionOrThrow(sessionId);
        agentConversationCleanupService.cancelRunning(sessionId);
        conversationRepository.deleteSession(sessionId);
        agentConversationCleanupService.deleteRecords(sessionId);
        activityEventService.deleteBySessionId(sessionId);
    }

    @Override
    public ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request) {
        return createMessageInternal(sessionId, request, ConversationProgressListener.NOOP);
    }

    private ConversationMessageResponseDTO createMessageInternal(String sessionId,
                                                                  ConversationMessageRequestDTO request,
                                                                  ConversationProgressListener progressListener) {
        return createMessageInternal(
                sessionId,
                request,
                progressListener,
                newTurnId(),
                newRunId());
    }

    private ConversationMessageResponseDTO createMessageInternal(String sessionId,
                                                                  ConversationMessageRequestDTO request,
                                                                  ConversationProgressListener progressListener,
                                                                  String turnId,
                                                                  String runId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        String titleAtRequestStart = session.getTitle();
        boolean autoGenerateTitle = shouldAutoGenerateTitle(session.getSessionId(), titleAtRequestStart);
        long requestStartedAt = System.currentTimeMillis();
        applyConversationScope(session, request);
        AnswerMode answerMode = resolveAnswerMode(request);
        request.setAnswerMode(answerMode.name());
        request.setPreferredModalities(resolveRequestedModalities(request.getPreferredModalities()));
        meterRegistry.counter("conversation.active.count").increment();
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
        turn.setCreatedAt(requestStartedAt);
        String generatedTitle = autoGenerateTitle
                ? buildAutoTitle(request.getQuery(), executionResult.rewrittenQuery())
                : null;
        try {
            Runnable persistence = () -> {
                if (!conversationRepository.lockActiveSession(session.getSessionId())) {
                    throw new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND);
                }
                conversationRepository.saveTurn(turn);
                boolean autoTitleUpdated = generatedTitle != null
                        && conversationRepository.updateAutoTitleIfUnchanged(
                        session.getSessionId(), titleAtRequestStart, generatedTitle, requestStartedAt);
                if (!autoTitleUpdated) {
                    conversationRepository.touchSessionIfNewer(session.getSessionId(), requestStartedAt);
                }
                if (executionResult.agentTask() != null) {
                    agentTaskRepository.save(newAgentTask(executionResult, turn, requestStartedAt));
                    submitTaskAfterCommit(executionResult.agentTask().taskId());
                }
            };
            transactionTemplate.executeWithoutResult(status -> persistence.run());
        } catch (RuntimeException e) {
            markAgentRunTurnFailed(executionResult.agentRunId(), e);
            throw e;
        }
        agentRunFinalizer.markTurnSaved(executionResult.agentRunId());
        boolean agentQuestion = executionResult.executionMode() == ConversationExecutionMode.AGENT;
        boolean traditionalKnowledgeBaseQuestion = executionResult.intent() != null
                && executionResult.intent().type() == ConversationIntentType.KB_QUERY;
        if (agentQuestion || traditionalKnowledgeBaseQuestion) {
            activityEventService.recordQuestionAsked(
                    session.getSessionId(),
                    turn.getTurnId(),
                    turn.getQuery(),
                    request.getKbIds());
        }
        meterRegistry.counter("conversation.turn.count").increment();

        ConversationSession persistedSession = loadSessionOrThrow(session.getSessionId());

        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId(session.getSessionId());
        response.setTurnId(turn.getTurnId());
        response.setTitle(persistedSession.getTitle());
        response.setSessionUpdatedAt(persistedSession.getUpdatedAt());
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
        response.setCreatedAt(requestStartedAt);
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
        String turnId = newTurnId();
        String runId = newRunId();
        emitter.onError(error -> clientDisconnected.set(true));
        emitter.onTimeout(() -> clientDisconnected.set(true));
        RequestUserContext context = UserContextHolder.get();
        streamExecutor.execute(() -> {
            UserContextHolder.set(context);
            try {
                Map<String, Object> initialTrace = new LinkedHashMap<>();
                initialTrace.put("stage", Boolean.TRUE.equals(request.getAgentEnabled()) ? "agent_thinking" : "routing");
                initialTrace.put("message", Boolean.TRUE.equals(request.getAgentEnabled()) ? "decision_started" : "started");
                initialTrace.put("turnId", turnId);
                if (Boolean.TRUE.equals(request.getAgentEnabled())) {
                    initialTrace.put("runId", runId);
                    initialTrace.put("details", Map.of(
                            "stepOrder", 1,
                            "turnId", turnId,
                            "decision", "ANALYZING"));
                }
                sendProgressEventSafely(emitter, initialTrace, clientDisconnected);
                ConversationMessageResponseDTO response = createMessageInternal(sessionId, request,
                        new ConversationProgressListener() {
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
                                sendProgressEventSafely(emitter,
                                        Map.of("stage", stage, "message", "started"), clientDisconnected);
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
                }, turnId, runId);
                if (StringUtils.hasText(response.getAgentRunId())) {
                    agentRuntimeSnapshotService.publishMessage(response.getAgentRunId(), response);
                }
                if (clientDisconnected.get()) {
                    log.debug("SSE client disconnected after Agent run persisted, sessionId={}, runId={}",
                            sessionId, activeRunId.get());
                    return;
                }
                streamAnswer(emitter, response.getAnswer());
                sendEvent(emitter, "citations", response.getCitations() == null ? List.of() : response.getCitations());
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("turnId", response.getTurnId());
                done.put("title", response.getTitle());
                done.put("sessionUpdatedAt", response.getSessionUpdatedAt());
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
    public ConversationTurnDTO getMessage(String sessionId, String turnId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        ConversationTurn turn = conversationRepository.findTurn(session.getSessionId(), turnId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        return toTurnDto(turn, toAgentTaskDto(turn.getAgentTaskId()));
    }

    @Override
    public ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId) {
        long totalStarted = System.nanoTime();
        long phaseStarted = System.nanoTime();
        ConversationSession session = loadSessionOrThrow(sessionId);
        recordHistoryPhase("session", phaseStarted);
        int boundedLimit = normalizeLimit(limit);

        ConversationTurnPosition before = null;
        if (StringUtils.hasText(beforeTurnId)) {
            phaseStarted = System.nanoTime();
            before = conversationRepository.findTurnPosition(session.getSessionId(), beforeTurnId)
                    .orElseThrow(() -> new IllegalArgumentException("beforeTurnId is invalid"));
            recordHistoryPhase("cursor", phaseStarted);
        }

        phaseStarted = System.nanoTime();
        List<ConversationTurn> candidates = conversationRepository.findTurnPage(
                session.getSessionId(), before, boundedLimit + 1);
        recordHistoryPhase("turns", phaseStarted);

        boolean hasMore = candidates.size() > boundedLimit;
        List<ConversationTurn> page = candidates.stream().limit(boundedLimit).toList();

        phaseStarted = System.nanoTime();
        Map<String, AgentTaskDTO> activeTasks = loadActiveTaskDtos(page);
        recordHistoryPhase("tasks", phaseStarted);

        List<ConversationTurn> chronologicalPage = page.stream()
                .sorted(Comparator.comparingLong(ConversationTurn::getCreatedAt)
                        .thenComparing(ConversationTurn::getTurnId))
                .toList();

        ConversationTurnListDTO response = new ConversationTurnListDTO();
        response.setSessionId(session.getSessionId());
        response.setHasMore(hasMore);
        response.setNextBeforeTurnId(hasMore && !page.isEmpty()
                ? page.getLast().getTurnId() : null);
        phaseStarted = System.nanoTime();
        response.setTurns(chronologicalPage.stream()
                .map(turn -> toTurnDto(turn, StringUtils.hasText(turn.getAgentTaskId())
                        ? activeTasks.get(turn.getAgentTaskId()) : null))
                .toList());
        recordHistoryPhase("mapping", phaseStarted);
        meterRegistry.summary("conversation.history.turns").record(page.size());
        meterRegistry.timer("conversation.history.latency", "phase", "total")
                .record(System.nanoTime() - totalStarted, TimeUnit.NANOSECONDS);

        return response;
    }

    private void recordHistoryPhase(String phase, long startedAt) {
        meterRegistry.timer("conversation.history.latency", "phase", phase)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private Map<String, AgentTaskDTO> loadActiveTaskDtos(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) return Map.of();
        Set<String> taskIds = new LinkedHashSet<>();
        for (ConversationTurn turn : turns) {
            if (AnswerStatus.PROCESSING.name().equals(turn.getAnswerStatus())
                    && StringUtils.hasText(turn.getAgentTaskId())) {
                taskIds.add(turn.getAgentTaskId());
            }
        }
        if (taskIds.isEmpty()) return Map.of();
        Map<String, AgentTaskDTO> tasks = new LinkedHashMap<>();
        for (AgentTask task : agentTaskRepository.findByIds(taskIds)) {
            tasks.put(task.getTaskId(), toAgentTaskDto(task));
        }
        return tasks;
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

    private ConversationTurnDTO toTurnDto(ConversationTurn turn, AgentTaskDTO agentTask) {
        ConversationTurnDTO dto = new ConversationTurnDTO();
        dto.setTurnId(turn.getTurnId());
        dto.setSessionId(turn.getSessionId());
        dto.setAgentRunId(turn.getAgentRunId());
        dto.setWorkflowVersion(turn.getWorkflowVersion());
        dto.setExecutionMode(StringUtils.hasText(turn.getExecutionMode()) ? turn.getExecutionMode() : "TRADITIONAL");
        dto.setAgentTask(agentTask);
        dto.setQuery(turn.getQuery());
        dto.setAnswer(turn.getAnswer());
        dto.setAssetScope(conversationTurnCodec.parseAssetScope(turn.getAssetScopeJson()));
        dto.setAnswerMode(turn.getAnswerMode());
        dto.setAnswerStatus(resolveTurnAnswerStatus(turn).name());
        dto.setAnswerFallbackReason(resolveTurnFallbackReason(turn));
        dto.setIntent(toIntentDto(turn));
        dto.setCitations(conversationTurnCodec.parseCitations(turn.getCitationsJson()));
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
        if (!StringUtils.hasText(taskId)) return null;
        return agentTaskRepository.findById(taskId).map(this::toAgentTaskDto).orElse(null);
    }

    private AgentTaskDTO toAgentTaskDto(AgentTask task) {
        AgentTaskDTO dto = new AgentTaskDTO(); dto.setTaskId(task.getTaskId()); dto.setType(task.getTaskType());
        dto.setStatus(task.getStatus()); dto.setProgress(task.getProgress()); dto.setCurrentStage(task.getCurrentStage());
        dto.setAnswer(task.getAnswer()); dto.setCitations(conversationTurnCodec.parseCitations(task.getCitationsJson()));
        dto.setErrorCode(task.getErrorCode()); dto.setErrorMessage(task.getErrorMessage()); return dto;
    }

    private void submitTaskAfterCommit(String taskId) {
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
            emitter.send(SseEmitter.event()
                    .name("trace")
                    .comment(SSE_TRACE_PADDING)
                    .data(data));
        } catch (IOException e) {
            throw new SseClientDisconnectedException(e);
        }
    }

    private void sendProgressEventSafely(SseEmitter emitter, Object data, AtomicBoolean clientDisconnected) {
        if (clientDisconnected.get()) return;
        try {
            sendProgressEvent(emitter, data);
        } catch (SseClientDisconnectedException e) {
            clientDisconnected.set(true);
        }
    }

    private void sendEventSafely(SseEmitter emitter,
                                 String event,
                                 Object data,
                                 AtomicBoolean clientDisconnected) {
        if (clientDisconnected.get()) return;
        try {
            sendEvent(emitter, event, data);
        } catch (IOException e) {
            clientDisconnected.set(true);
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

    private String encodeSessionListCursor(ConversationSessionPosition position) {
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("version", SESSION_LIST_CURSOR_VERSION);
            payload.put("updatedAt", position.updatedAt());
            payload.put("sessionId", position.sessionId());
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode session list cursor", e);
        }
    }

    private ConversationSessionPosition decodeSessionListCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            String normalized = cursor.trim();
            if (normalized.length() > MAX_SESSION_LIST_CURSOR_LENGTH) {
                throw invalidSessionListCursor();
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(normalized));
            JsonNode version = payload == null ? null : payload.get("version");
            JsonNode updatedAt = payload == null ? null : payload.get("updatedAt");
            JsonNode sessionId = payload == null ? null : payload.get("sessionId");
            if (version == null || !version.isIntegralNumber()
                    || !version.canConvertToInt()
                    || version.intValue() != SESSION_LIST_CURSOR_VERSION
                    || updatedAt == null || !updatedAt.isIntegralNumber()
                    || !updatedAt.canConvertToLong()
                    || updatedAt.longValue() < 0
                    || updatedAt.longValue() > MAX_SESSION_CURSOR_UPDATED_AT
                    || sessionId == null || !sessionId.isTextual()
                    || !StringUtils.hasText(sessionId.textValue())
                    || !sessionId.textValue().equals(sessionId.textValue().trim())
                    || sessionId.textValue().length() > 64) {
                throw invalidSessionListCursor();
            }
            return new ConversationSessionPosition(sessionId.textValue(), updatedAt.longValue());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalidSessionListCursor();
        }
    }

    private BusinessException invalidSessionListCursor() {
        return new BusinessException(ApiError.INVALID_REQUEST, "cursor is invalid");
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
        try {
            agentRunFinalizer.markTurnFailed(runId);
        } catch (RuntimeException traceError) {
            original.addSuppressed(traceError);
        }
    }
}
