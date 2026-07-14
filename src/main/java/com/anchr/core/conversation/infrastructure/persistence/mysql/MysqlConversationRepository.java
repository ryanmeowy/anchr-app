package com.anchr.core.conversation.infrastructure.persistence.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.domain.model.ConversationRole;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationSessionStatus;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MysqlConversationRepository implements ConversationRepository {

    private static final int MAX_RECENT_LIMIT = 100;
    private static final int MAX_SESSION_LIST_LIMIT = 200;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ConversationMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void saveSession(ConversationSession session) {
        if (session == null || !StringUtils.hasText(session.getSessionId())) {
            throw new IllegalArgumentException("sessionId cannot be empty");
        }
        mapper.upsertSession(toSessionRecord(session));
    }

    @Override
    public Optional<ConversationSession> findSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        return mapper.findSession(sessionId).map(this::toSessionDomain);
    }

    @Override
    public List<ConversationSession> findRecentSessions(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_SESSION_LIST_LIMIT));
        return mapper.findRecentSessions(userId, boundedLimit).stream()
                .map(this::toSessionDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            mapper.softDeleteTurns(sessionId);
            mapper.softDeleteSession(sessionId);
        }
    }

    @Override
    public void saveTurn(ConversationTurn turn) {
        if (turn == null || !StringUtils.hasText(turn.getSessionId()) || !StringUtils.hasText(turn.getTurnId())) {
            throw new IllegalArgumentException("sessionId and turnId cannot be empty");
        }
        mapper.upsertTurn(toTurnRecord(turn));
    }

    @Override
    public Optional<ConversationTurn> findTurn(String sessionId, String turnId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(turnId)) {
            return Optional.empty();
        }
        return mapper.findTurn(sessionId, turnId).map(this::toTurnDomain);
    }

    @Override
    public List<ConversationTurn> findRecentTurns(String sessionId, int limit) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        return mapper.findRecentTurns(sessionId, boundedLimit).stream()
                .map(this::toTurnDomain)
                .toList();
    }

    private ConversationSessionRecord toSessionRecord(ConversationSession session) {
        ConversationSessionRecord record = new ConversationSessionRecord();
        record.setSessionId(session.getSessionId());
        record.setUserId(session.getUserId());
        record.setTitle(session.getTitle());
        record.setStatus(session.getStatus().name());
        record.setKbScope(toJson(session.getKbScope()));
        record.setAssetScope(toJson(session.getAssetScope()));
        record.setCreatedAt(toLocalDateTime(session.getCreatedAt()));
        record.setUpdatedAt(toLocalDateTime(session.getUpdatedAt()));
        return record;
    }

    private ConversationSession toSessionDomain(ConversationSessionRecord record) {
        ConversationSession session = new ConversationSession();
        session.setSessionId(record.getSessionId());
        session.setUserId(record.getUserId());
        session.setTitle(record.getTitle());
        session.setStatus(ConversationSessionStatus.valueOf(record.getStatus()));
        session.setKbScope(parseStringList(record.getKbScope()));
        session.setAssetScope(parseStringList(record.getAssetScope()));
        session.setCreatedAt(toEpochMillis(record.getCreatedAt()));
        session.setUpdatedAt(toEpochMillis(record.getUpdatedAt()));
        session.setExpiresAt(null);
        return session;
    }

    private ConversationTurnRecord toTurnRecord(ConversationTurn turn) {
        ConversationTurnRecord record = new ConversationTurnRecord();
        record.setTurnId(turn.getTurnId());
        record.setSessionId(turn.getSessionId());
        record.setRole(turn.getRole().name());
        record.setQuery(turn.getQuery());
        record.setRewrittenQuery(turn.getRewrittenQuery());
        record.setAnswer(turn.getAnswer());
        record.setKbScope(normalizeJson(turn.getKbScopeJson(), "[]"));
        record.setAssetScope(normalizeJson(turn.getAssetScopeJson(), "[]"));
        record.setAnswerMode(turn.getAnswerMode());
        record.setAnswerStatus(turn.getAnswerStatus());
        record.setAnswerFallbackReason(turn.getAnswerFallbackReason());
        record.setCitations(normalizeJson(turn.getCitationsJson(), "[]"));
        record.setResultCards(normalizeJson(turn.getResultCardsJson(), "[]"));
        record.setRetrievalTrace(normalizeJson(turn.getRetrievalTraceJson(), "{}"));
        record.setCreatedAt(toLocalDateTime(turn.getCreatedAt()));
        return record;
    }

    private ConversationTurn toTurnDomain(ConversationTurnRecord record) {
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(record.getTurnId());
        turn.setSessionId(record.getSessionId());
        turn.setRole(ConversationRole.valueOf(record.getRole()));
        turn.setQuery(record.getQuery());
        turn.setRewrittenQuery(record.getRewrittenQuery());
        turn.setAnswer(record.getAnswer());
        turn.setKbScopeJson(record.getKbScope());
        turn.setAssetScopeJson(record.getAssetScope());
        turn.setAnswerMode(record.getAnswerMode());
        turn.setAnswerStatus(record.getAnswerStatus());
        turn.setAnswerFallbackReason(record.getAnswerFallbackReason());
        turn.setCitationsJson(record.getCitations());
        turn.setResultCardsJson(record.getResultCards());
        turn.setRetrievalTraceJson(record.getRetrievalTrace());
        turn.setCreatedAt(toEpochMillis(record.getCreatedAt()));
        return turn;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize conversation scope", e);
        }
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse conversation scope", e);
        }
    }

    private String normalizeJson(String json, String fallback) {
        return StringUtils.hasText(json) ? json : fallback;
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
