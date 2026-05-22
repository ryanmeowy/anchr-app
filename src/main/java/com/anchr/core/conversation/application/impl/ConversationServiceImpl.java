package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.activity.application.ActivityEventService;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.FollowUpQuestionService;
import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.application.assembler.ConversationRetrievalTraceBuilder;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.domain.model.ConversationRole;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.anchr.core.search.application.KbScopeResolver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Default conversation application service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_TURN_LIMIT = 20;
    private static final int MAX_TURN_LIMIT = 100;
    private static final int DEFAULT_SESSION_LIST_LIMIT = 20;
    private static final int MAX_SESSION_LIST_LIMIT = 50;
    private static final long SESSION_TTL_MILLIS = TimeUnit.DAYS.toMillis(30);
    private static final int AUTO_TITLE_MAX_LENGTH = 128;
    private static final int LAST_MESSAGE_PREVIEW_MAX_LENGTH = 80;
    private static final String SINGLE_USER_ID = "single_user";

    private final ConversationRepository conversationRepository;
    private final ConversationMessagePipeline conversationMessagePipeline;
    private final FollowUpQuestionService followUpQuestionService;
    private final ConversationTurnCodec conversationTurnCodec;
    private final ConversationRetrievalTraceBuilder conversationRetrievalTraceBuilder;
    private final KbScopeResolver kbScopeResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ActivityEventService activityEventService;
    @Qualifier("ingestionTaskExecutor")
    private final Executor streamExecutor;

    @Override
    public ConversationSessionDTO createSession(ConversationCreateRequestDTO request) {
        long now = System.currentTimeMillis();
        String title = safeTrim(request.getTitle());
        ConversationSession session = ConversationSession.createActive(
                newSessionId(),
                SINGLE_USER_ID,
                title,
                now,
                resolveExpiresAt(now)
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
        session.touch(now, resolveExpiresAt(now));
        conversationRepository.saveSession(session);
        return toSessionDto(session);
    }

    @Override
    public void deleteSession(String sessionId) {
        loadSessionOrThrow(sessionId);
        conversationRepository.deleteSession(sessionId);
    }

    @Override
    public ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        long now = System.currentTimeMillis();
        applyConversationScope(session, request);
        boolean shouldAutoTitle = shouldAutoGenerateTitle(session.getSessionId(), session.getTitle());
        meterRegistry.counter("conversation.active.count").increment();
        ConversationMessagePipelineResult pipelineResult = conversationMessagePipeline.execute(session.getSessionId(), request);

        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(newTurnId());
        turn.setSessionId(session.getSessionId());
        turn.setRole(ConversationRole.USER);
        turn.setQuery(request.getQuery().trim());
        turn.setRewrittenQuery(pipelineResult.rewriteResult().getRewrittenQuery());
        turn.setAnswer(pipelineResult.answerGenerationResult().getAnswerText());
        turn.setKbScopeJson(conversationTurnCodec.serializeKbScope(request.getKbIds()));
        turn.setAnswerMode(resolveAnswerMode(request));
        turn.setCitationsJson(conversationTurnCodec.serializeCitations(pipelineResult.answerCitations()));
        turn.setResultCardsJson(conversationTurnCodec.serializeResultCards(pipelineResult.resultCards()));
        turn.setRetrievalTraceJson(conversationRetrievalTraceBuilder.buildTraceJson(
                request,
                pipelineResult.rewriteResult(),
                pipelineResult.retrievalResult(),
                pipelineResult.answerGenerationResult()
        ));
        turn.setCreatedAt(now);
        conversationRepository.saveTurn(turn);
        activityEventService.recordQuestionAsked(
                session.getSessionId(),
                turn.getTurnId(),
                turn.getQuery(),
                request.getKbIds());
        meterRegistry.counter("conversation.turn.count").increment();

        if (shouldAutoTitle) {
            session.setTitle(buildAutoTitle(request.getQuery(), pipelineResult.rewriteResult().getRewrittenQuery()));
        }
        session.touch(now, resolveExpiresAt(now));
        conversationRepository.saveSession(session);

        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId(session.getSessionId());
        response.setTurnId(turn.getTurnId());
        response.setRewrittenQuery(pipelineResult.rewriteResult().getRewrittenQuery());
        response.setAnswer(turn.getAnswer());
        response.setKbScope(request.getKbIds());
        response.setAnswerMode(turn.getAnswerMode());
        response.setRetrievalStage("ANSWERED");
        response.setCitations(conversationTurnCodec.toCitationDTOs(pipelineResult.answerCitations()));
        response.setResultCards(pipelineResult.resultCards());
        List<String> suggestedQuestions = followUpQuestionService.generate(
                request.getQuery().trim(),
                pipelineResult.rewriteResult().getRewrittenQuery(),
                pipelineResult.answerCitations()
        );
        response.setSuggestedQuestions(suggestedQuestions);
        response.setRetrievalTrace(conversationRetrievalTraceBuilder.buildTraceDto(
                request,
                pipelineResult.rewriteResult(),
                pipelineResult.retrievalResult(),
                pipelineResult.answerGenerationResult()
        ));
        response.setCreatedAt(now);
        meterRegistry.summary("answer.citation.count").record(pipelineResult.answerCitations().size());
        if (pipelineResult.answerCitations().isEmpty()) {
            meterRegistry.counter("answer.citation.empty.count").increment();
        }
        return response;
    }

    @Override
    public SseEmitter streamMessage(String sessionId, ConversationMessageRequestDTO request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        RequestUserContext context = UserContextHolder.get();
        streamExecutor.execute(() -> {
            UserContextHolder.set(context);
            try {
                sendEvent(emitter, "trace", Map.of("stage", "retrieval", "message", "started"));
                ConversationMessageResponseDTO response = createMessage(sessionId, request);
                streamAnswer(emitter, response.getAnswer());
                sendEvent(emitter, "citations", response.getCitations() == null ? List.of() : response.getCitations());
                sendEvent(emitter, "done", Map.of("turnId", response.getTurnId(), "kbScope", response.getKbScope()));
                emitter.complete();
            } catch (BusinessException e) {
                sendError(emitter, e.getError() == null ? ApiError.INTERNAL_ERROR.name() : e.getError().name(), e.getMessage());
            } catch (Exception e) {
                sendError(emitter, ApiError.INTERNAL_ERROR.name(), ApiError.INTERNAL_ERROR.getMessage());
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
        dto.setQuery(turn.getQuery());
        dto.setRewrittenQuery(turn.getRewrittenQuery());
        dto.setAnswer(turn.getAnswer());
        dto.setKbScope(conversationTurnCodec.parseKbScope(turn.getKbScopeJson()));
        dto.setAnswerMode(turn.getAnswerMode());
        dto.setCitations(conversationTurnCodec.parseCitations(turn.getCitationsJson()));
        dto.setResultCards(conversationTurnCodec.parseResultCards(turn.getResultCardsJson()));
        dto.setCreatedAt(turn.getCreatedAt());
        return dto;
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

    private long resolveExpiresAt(long now) {
        return now + SESSION_TTL_MILLIS;
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
        if ((requested == null || requested.isEmpty()) && session.getKbScope() != null && !session.getKbScope().isEmpty()) {
            request.setKbIds(session.getKbScope());
            return;
        }
        request.setKbIds(kbScopeResolver.resolveVisibleKbIds(requested));
    }

    private String resolveAnswerMode(ConversationMessageRequestDTO request) {
        return StringUtils.hasText(request.getAnswerMode()) ? request.getAnswerMode().trim() : "STRICT";
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
            try {
                emitter.completeWithError(e);
            } catch (Exception completeError) {
                log.warn("failed to complete SSE emitter after error event failure, code={}", code, completeError);
            }
        } catch (Exception e) {
            log.warn("failed to send SSE error event, code={}", code, e);
            try {
                emitter.completeWithError(e);
            } catch (Exception completeError) {
                log.warn("failed to complete SSE emitter after runtime error, code={}", code, completeError);
            }
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
}
