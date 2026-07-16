package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for conversation session and turns.
 */
public interface ConversationRepository {

    void saveSession(ConversationSession session);

    Optional<ConversationSession> findSession(String sessionId);

    /**
     * Locks and verifies an active session before a generated result is persisted.
     * The default keeps non-database adapters and legacy tests compatible.
     */
    default boolean lockActiveSession(String sessionId) {
        return findSession(sessionId).isPresent();
    }

    List<ConversationSession> findRecentSessions(String userId, int limit);

    void deleteSession(String sessionId);

    void saveTurn(ConversationTurn turn);

    Optional<ConversationTurn> findTurn(String sessionId, String turnId);

    List<ConversationTurn> findRecentTurns(String sessionId, int limit);
}
