package com.anchr.core.conversation.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.acl.ConversationActivityAcl;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.agent.AgentRunFinalizer;
import com.anchr.core.conversation.application.agent.AgentTaskProcessor;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationExecutionMode;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.AgentTaskStatus;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationIntentDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConversationMessageUseCase {

    private static final int AUTO_TITLE_MAX_LENGTH = 128;
    private static final String SINGLE_USER_ID = "single_user";

    private final ConversationRepository conversationRepository;
    private final ConversationMessageOrchestrator conversationMessageOrchestrator;
    private final ConversationTurnCodec turnCodec;
    private final ConversationRetrievalTraceBuilder retrievalTraceBuilder;
    private final ConversationKnowledgeAcl conversationKnowledgeAcl;
    private final MeterRegistry meterRegistry;
    private final ConversationActivityAcl conversationActivityAcl;
    private final AgentTaskRepository agentTaskRepository;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunFinalizer agentRunFinalizer;
    private final AgentTaskProcessor agentTaskProcessor;
    private final ConversationAgentTaskDtoAssembler agentTaskAssembler;

    public ConversationMessageResponseDTO execute(
            String sessionId,
            ConversationMessageRequestDTO request
    ) {
        return execute(sessionId, request, ConversationProgressListener.NOOP);
    }

    public ConversationMessageResponseDTO execute(
            String sessionId,
            ConversationMessageRequestDTO request,
            ConversationProgressListener progressListener
    ) {
        String turnId = newTurnId();
        String runId = newRunId();
        progressListener.onExecutionStarted(turnId, runId);
        return execute(sessionId, request, progressListener, turnId, runId);
    }

    private ConversationMessageResponseDTO execute(
            String sessionId,
            ConversationMessageRequestDTO request,
            ConversationProgressListener progressListener,
            String turnId,
            String runId
    ) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        String titleAtRequestStart = session.getTitle();
        boolean autoGenerateTitle =
                shouldAutoGenerateTitle(session.getSessionId(), titleAtRequestStart);
        long requestStartedAt = System.currentTimeMillis();
        applyConversationScope(session, request);
        AnswerMode answerMode = AnswerMode.from(request.getAnswerMode());
        request.setAnswerMode(answerMode.name());
        request.setPreferredModalities(
                resolveRequestedModalities(request.getPreferredModalities()));
        meterRegistry.counter("conversation.active.count").increment();
        ConversationExecutionResult executionResult = conversationMessageOrchestrator.execute(
                session.getSessionId(), turnId, runId, request, progressListener);
        ConversationTurn turn = buildTurn(
                session, request, answerMode, executionResult, turnId, requestStartedAt);
        String generatedTitle = autoGenerateTitle
                ? buildAutoTitle(request.getQuery(), executionResult.rewrittenQuery())
                : null;

        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!conversationRepository.lockActiveSession(session.getSessionId())) {
                    throw new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND);
                }
                conversationRepository.saveTurn(turn);
                boolean autoTitleUpdated = generatedTitle != null
                        && conversationRepository.updateAutoTitleIfUnchanged(
                        session.getSessionId(),
                        titleAtRequestStart,
                        generatedTitle,
                        requestStartedAt);
                if (!autoTitleUpdated) {
                    conversationRepository.touchSessionIfNewer(
                            session.getSessionId(), requestStartedAt);
                }
                if (executionResult.agentTask() != null) {
                    agentTaskRepository.save(
                            newAgentTask(executionResult, turn, requestStartedAt));
                    submitTaskAfterCommit(executionResult.agentTask().taskId());
                }
            });
        } catch (RuntimeException exception) {
            markAgentRunTurnFailed(executionResult.agentRunId(), exception);
            throw exception;
        }

        agentRunFinalizer.markTurnSaved(executionResult.agentRunId());
        recordQuestionActivity(session, request, turn, executionResult);
        meterRegistry.counter("conversation.turn.count").increment();
        ConversationSession persistedSession = loadSessionOrThrow(session.getSessionId());
        return buildResponse(
                request, executionResult, turn, persistedSession, requestStartedAt);
    }

    private ConversationTurn buildTurn(
            ConversationSession session,
            ConversationMessageRequestDTO request,
            AnswerMode answerMode,
            ConversationExecutionResult result,
            String turnId,
            long requestStartedAt
    ) {
        AnswerStatus answerStatus = result.answerStatus();
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(turnId);
        turn.setSessionId(session.getSessionId());
        turn.setQuery(request.getQuery().trim());
        turn.setRewrittenQuery(result.rewrittenQuery());
        turn.setAnswer(result.answer());
        turn.setKbScopeJson(turnCodec.serializeKbScope(request.getKbIds()));
        turn.setAssetScopeJson(turnCodec.serializeAssetScope(request.getAssetIdList()));
        turn.setAnswerMode(answerMode.name());
        turn.setAnswerStatus(answerStatus.name());
        turn.setAnswerFallbackReason(result.fallbackReason());
        applyIntent(turn, result.intent());
        turn.setCitationsJson(turnCodec.serializeCitations(result.citations()));
        turn.setResultCardsJson(turnCodec.serializeResultCards(result.resultCards()));
        turn.setRetrievalTraceJson(buildRetrievalTraceJson(request, result));
        turn.setAgentRunId(result.agentRunId());
        turn.setExecutionMode(result.executionMode().name());
        turn.setAgentTaskId(
                result.agentTask() == null ? null : result.agentTask().taskId());
        turn.setCreatedAt(requestStartedAt);
        return turn;
    }

    private ConversationMessageResponseDTO buildResponse(
            ConversationMessageRequestDTO request,
            ConversationExecutionResult result,
            ConversationTurn turn,
            ConversationSession persistedSession,
            long requestStartedAt
    ) {
        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId(turn.getSessionId());
        response.setTurnId(turn.getTurnId());
        response.setTitle(persistedSession.getTitle());
        response.setSessionUpdatedAt(persistedSession.getUpdatedAt());
        response.setAgentRunId(turn.getAgentRunId());
        response.setExecutionMode(turn.getExecutionMode());
        response.setAgentTask(toAgentTaskDto(turn.getAgentTaskId()));
        response.setRewrittenQuery(result.rewrittenQuery());
        response.setAnswer(turn.getAnswer());
        response.setKbScope(request.getKbIds());
        response.setAssetScope(request.getAssetIdList());
        response.setAnswerMode(turn.getAnswerMode());
        response.setAnswerStatus(turn.getAnswerStatus());
        response.setAnswerFallbackReason(turn.getAnswerFallbackReason());
        response.setRetrievalStage(result.retrievalExecuted() ? "ANSWERED" : "SKIPPED");
        response.setIntent(toIntentDto(result.intent()));
        response.setCitations(turnCodec.toCitationDTOs(result.citations()));
        response.setResultCards(result.resultCards());
        response.setRetrievalTrace(buildRetrievalTraceDto(request, result));
        response.setCreatedAt(requestStartedAt);
        if (result.retrievalExecuted()) {
            meterRegistry.summary("answer.citation.count").record(result.citations().size());
            if (result.citations().isEmpty()) {
                meterRegistry.counter("answer.citation.empty.count").increment();
            }
        }
        return response;
    }

    private void recordQuestionActivity(
            ConversationSession session,
            ConversationMessageRequestDTO request,
            ConversationTurn turn,
            ConversationExecutionResult result
    ) {
        boolean agentQuestion =
                result.executionMode() == ConversationExecutionMode.AGENT;
        boolean knowledgeQuestion = result.intent() != null
                && result.intent().type() == ConversationIntentType.KB_QUERY;
        if (agentQuestion || knowledgeQuestion) {
            conversationActivityAcl.recordQuestionAsked(
                    session.getSessionId(),
                    turn.getTurnId(),
                    turn.getQuery(),
                    request.getKbIds());
        }
    }

    private ConversationSession loadSessionOrThrow(String sessionId) {
        return conversationRepository.findSession(sessionId)
                .orElseThrow(() -> new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));
    }

    private void applyConversationScope(
            ConversationSession session,
            ConversationMessageRequestDTO request
    ) {
        List<String> requested = request.getKbIds();
        if (CollectionUtils.isEmpty(requested)
                && !CollectionUtils.isEmpty(session.getKbScope())) {
            request.setKbIds(session.getKbScope());
        } else {
            request.setKbIds(
                    conversationKnowledgeAcl.resolveVisibleKbIds(requested));
        }
    }

    private List<String> resolveRequestedModalities(List<String> requestedModalities) {
        if (requestedModalities == null || requestedModalities.isEmpty()) {
            return List.of("MIXED");
        }
        List<String> normalized = requestedModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of("MIXED") : normalized;
    }

    private void applyIntent(ConversationTurn turn, ConversationIntentResult intent) {
        if (intent == null) {
            turn.setIntentType(null);
            turn.setIntentConfidence(null);
            turn.setIntentReason(null);
            turn.setIntentSource(null);
            turn.setIntentFallback(false);
            return;
        }
        turn.setIntentType(intent.type().name());
        turn.setIntentConfidence(intent.confidence());
        turn.setIntentReason(truncate(intent.reason(), 255));
        turn.setIntentSource(intent.source().name());
        turn.setIntentFallback(intent.fallbackUsed());
    }

    private ConversationIntentDTO toIntentDto(ConversationIntentResult intent) {
        if (intent == null) {
            return null;
        }
        ConversationIntentDTO dto = new ConversationIntentDTO();
        dto.setType(intent.type().name());
        dto.setConfidence(intent.confidence());
        dto.setReason(intent.reason());
        dto.setSource(intent.source().name());
        dto.setFallbackUsed(intent.fallbackUsed());
        dto.setRetrievalRequired(intent.retrievalRequired());
        return dto;
    }

    private AgentTask newAgentTask(
            ConversationExecutionResult result,
            ConversationTurn turn,
            long now
    ) {
        AgentTask task = new AgentTask();
        task.setTaskId(result.agentTask().taskId());
        task.setRunId(result.agentRunId());
        task.setTurnId(turn.getTurnId());
        task.setSessionId(turn.getSessionId());
        task.setUserId(SINGLE_USER_ID);
        task.setTaskType(result.agentTask().type());
        task.setStatus(AgentTaskStatus.PENDING.name());
        task.setProgress(0);
        task.setCurrentStage("QUEUED");
        task.setRequestJson(result.agentTask().requestJson());
        task.setCitationsJson("[]");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private AgentTaskDTO toAgentTaskDto(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return agentTaskRepository.findById(taskId)
                .map(agentTaskAssembler::toDto)
                .orElse(null);
    }

    private void submitTaskAfterCommit(String taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            agentTaskProcessor.trigger(taskId);
                        }
                    });
        } else {
            agentTaskProcessor.trigger(taskId);
        }
    }

    private String buildRetrievalTraceJson(
            ConversationMessageRequestDTO request,
            ConversationExecutionResult result
    ) {
        ConversationMessagePipelineResult rag = result.ragResult();
        if (rag == null) {
            return "{}";
        }
        return retrievalTraceBuilder.buildTraceJson(
                request,
                rag.rewriteResult(),
                rag.retrievalResult(),
                rag.answerGenerationResult());
    }

    private ConversationMessageResponseDTO.RetrievalTraceDTO buildRetrievalTraceDto(
            ConversationMessageRequestDTO request,
            ConversationExecutionResult result
    ) {
        ConversationMessagePipelineResult rag = result.ragResult();
        if (rag == null) {
            return null;
        }
        return retrievalTraceBuilder.buildTraceDto(
                request,
                rag.rewriteResult(),
                rag.retrievalResult(),
                rag.answerGenerationResult());
    }

    private boolean shouldAutoGenerateTitle(String sessionId, String existingTitle) {
        return !StringUtils.hasText(existingTitle)
                && conversationRepository.findRecentTurns(sessionId, 1).isEmpty();
    }

    private String buildAutoTitle(String query, String rewrittenQuery) {
        String candidate = StringUtils.hasText(rewrittenQuery) ? rewrittenQuery : query;
        if (!StringUtils.hasText(candidate)) {
            return "新会话";
        }
        String normalized = candidate.trim().replaceAll("\\s+", " ");
        return normalized.length() <= AUTO_TITLE_MAX_LENGTH
                ? normalized
                : normalized.substring(0, AUTO_TITLE_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength
                ? trimmed : trimmed.substring(0, maxLength);
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
