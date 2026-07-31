package com.anchr.core.conversation.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationIntentResult;
import com.anchr.core.conversation.application.model.ConversationIntentSource;
import com.anchr.core.conversation.application.model.ConversationIntentType;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationTurnPosition;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationIntentDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
final class ConversationHistoryQuery {

    private static final int DEFAULT_TURN_LIMIT = 20;
    private static final int MAX_TURN_LIMIT = 100;

    private final ConversationRepository conversationRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final ConversationTurnCodec turnCodec;
    private final ConversationAgentTaskDtoAssembler agentTaskAssembler;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    ConversationTurnDTO get(String sessionId, String turnId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        ConversationTurn turn = conversationRepository.findTurn(session.getSessionId(), turnId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        AgentTaskDTO task = StringUtils.hasText(turn.getAgentTaskId())
                ? agentTaskRepository.findById(turn.getAgentTaskId())
                        .map(agentTaskAssembler::toDto)
                        .orElse(null)
                : null;
        return toTurnDto(turn, task);
    }

    ConversationTurnListDTO list(String sessionId, Integer limit, String beforeTurnId) {
        long totalStarted = System.nanoTime();
        long phaseStarted = System.nanoTime();
        ConversationSession session = loadSessionOrThrow(sessionId);
        recordPhase("session", phaseStarted);
        int boundedLimit = normalizeLimit(limit);

        ConversationTurnPosition before = null;
        if (StringUtils.hasText(beforeTurnId)) {
            phaseStarted = System.nanoTime();
            before = conversationRepository.findTurnPosition(
                            session.getSessionId(), beforeTurnId)
                    .orElseThrow(() -> new IllegalArgumentException("beforeTurnId is invalid"));
            recordPhase("cursor", phaseStarted);
        }

        phaseStarted = System.nanoTime();
        List<ConversationTurn> candidates = conversationRepository.findTurnPage(
                session.getSessionId(), before, boundedLimit + 1);
        recordPhase("turns", phaseStarted);
        boolean hasMore = candidates.size() > boundedLimit;
        List<ConversationTurn> page = candidates.stream().limit(boundedLimit).toList();

        phaseStarted = System.nanoTime();
        Map<String, AgentTaskDTO> activeTasks = loadActiveTaskDtos(page);
        recordPhase("tasks", phaseStarted);
        List<ConversationTurn> chronologicalPage = page.stream()
                .sorted(Comparator.comparingLong(ConversationTurn::getCreatedAt)
                        .thenComparing(ConversationTurn::getTurnId))
                .toList();

        ConversationTurnListDTO response = new ConversationTurnListDTO();
        response.setSessionId(session.getSessionId());
        response.setHasMore(hasMore);
        response.setNextBeforeTurnId(
                hasMore && !page.isEmpty() ? page.getLast().getTurnId() : null);
        phaseStarted = System.nanoTime();
        response.setTurns(chronologicalPage.stream()
                .map(turn -> toTurnDto(
                        turn,
                        StringUtils.hasText(turn.getAgentTaskId())
                                ? activeTasks.get(turn.getAgentTaskId()) : null))
                .toList());
        recordPhase("mapping", phaseStarted);
        meterRegistry.summary("conversation.history.turns").record(page.size());
        meterRegistry.timer("conversation.history.latency", "phase", "total")
                .record(System.nanoTime() - totalStarted, TimeUnit.NANOSECONDS);
        return response;
    }

    private void recordPhase(String phase, long startedAt) {
        meterRegistry.timer("conversation.history.latency", "phase", phase)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private Map<String, AgentTaskDTO> loadActiveTaskDtos(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return Map.of();
        }
        Set<String> taskIds = new LinkedHashSet<>();
        for (ConversationTurn turn : turns) {
            if (AnswerStatus.PROCESSING.name().equals(turn.getAnswerStatus())
                    && StringUtils.hasText(turn.getAgentTaskId())) {
                taskIds.add(turn.getAgentTaskId());
            }
        }
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentTaskDTO> tasks = new LinkedHashMap<>();
        for (AgentTask task : agentTaskRepository.findByIds(taskIds)) {
            tasks.put(task.getTaskId(), agentTaskAssembler.toDto(task));
        }
        return tasks;
    }

    private ConversationSession loadSessionOrThrow(String sessionId) {
        return conversationRepository.findSession(sessionId)
                .orElseThrow(() -> new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));
    }

    private ConversationTurnDTO toTurnDto(ConversationTurn turn, AgentTaskDTO agentTask) {
        ConversationTurnDTO dto = new ConversationTurnDTO();
        dto.setTurnId(turn.getTurnId());
        dto.setSessionId(turn.getSessionId());
        dto.setAgentRunId(turn.getAgentRunId());
        dto.setExecutionMode(StringUtils.hasText(turn.getExecutionMode())
                ? turn.getExecutionMode() : "TRADITIONAL");
        dto.setAgentTask(agentTask);
        dto.setQuery(turn.getQuery());
        dto.setAnswer(turn.getAnswer());
        dto.setAssetScope(turnCodec.parseAssetScope(turn.getAssetScopeJson()));
        dto.setAnswerMode(turn.getAnswerMode());
        dto.setAnswerStatus(resolveAnswerStatus(turn).name());
        dto.setAnswerFallbackReason(resolveFallbackReason(turn));
        dto.setIntent(toIntentDto(turn));
        dto.setCitations(turnCodec.parseCitations(turn.getCitationsJson()));
        return dto;
    }

    private AnswerStatus resolveAnswerStatus(ConversationTurn turn) {
        if (StringUtils.hasText(turn.getAnswerStatus())) {
            try {
                return AnswerStatus.valueOf(turn.getAnswerStatus().trim());
            } catch (IllegalArgumentException ignored) {
                // Preserve legacy trace inference.
            }
        }
        Map<?, ?> trace = parseRetrievalTrace(turn.getRetrievalTraceJson());
        if (!Boolean.TRUE.equals(trace.get("answerFallback"))) {
            return AnswerStatus.ANSWERED;
        }
        String reason = trace.get("answerFallbackReason") instanceof String value ? value : null;
        return StringUtils.hasText(reason) && reason.startsWith("no_evidence")
                ? AnswerStatus.NO_EVIDENCE
                : AnswerStatus.MODEL_FALLBACK;
    }

    private String resolveFallbackReason(ConversationTurn turn) {
        if (StringUtils.hasText(turn.getAnswerFallbackReason())) {
            return turn.getAnswerFallbackReason();
        }
        Object reason = parseRetrievalTrace(
                turn.getRetrievalTraceJson()).get("answerFallbackReason");
        return reason instanceof String value && StringUtils.hasText(value) ? value : null;
    }

    private ConversationIntentDTO toIntentDto(ConversationTurn turn) {
        if ("AGENT".equals(turn.getExecutionMode())
                && !StringUtils.hasText(turn.getIntentType())) {
            return null;
        }
        ConversationIntentType type;
        ConversationIntentSource source;
        try {
            type = StringUtils.hasText(turn.getIntentType())
                    ? ConversationIntentType.valueOf(turn.getIntentType())
                    : ConversationIntentType.KB_QUERY;
        } catch (IllegalArgumentException exception) {
            type = ConversationIntentType.KB_QUERY;
        }
        try {
            source = StringUtils.hasText(turn.getIntentSource())
                    ? ConversationIntentSource.valueOf(turn.getIntentSource())
                    : ConversationIntentSource.LEGACY;
        } catch (IllegalArgumentException exception) {
            source = ConversationIntentSource.LEGACY;
        }
        return toIntentDto(new ConversationIntentResult(
                type,
                turn.getIntentConfidence() == null ? 0D : turn.getIntentConfidence(),
                turn.getIntentReason(),
                source,
                turn.isIntentFallback()));
    }

    private ConversationIntentDTO toIntentDto(ConversationIntentResult intent) {
        ConversationIntentDTO dto = new ConversationIntentDTO();
        dto.setType(intent.type().name());
        dto.setConfidence(intent.confidence());
        dto.setReason(intent.reason());
        dto.setSource(intent.source().name());
        dto.setFallbackUsed(intent.fallbackUsed());
        dto.setRetrievalRequired(intent.retrievalRequired());
        return dto;
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
}
